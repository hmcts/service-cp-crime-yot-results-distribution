# Architecture & Domain Rules

This service is a **message-driven pipeline with a scheduled second leg**, not a REST application.
There is **no business REST API**: no hearing is submitted over HTTP, no register is read out over
HTTP, no batch is created by a caller. Everything below assumes that shape.

Since increment 005 it does serve one HTTP surface besides actuator — the **operations API** under
`/operations/**`, the named operator actions that replaced the CLI, each behind
`cp-auth-rules-filter` and `cp-audit-filter-springboot` (see "The operations API" below). The CLI is
gone: `batch/cli/`, `config/CliModeConfig`, the `yotresultsdistribution.cli` property and the
`docker/startup.sh` dispatch are removed, and the image starts the application, full stop.

Since increment 002 the service owns **both halves** of the court-register flow: the intake half
ported from the function app, and the downstream half absorbed from `cpp-context-progression`.
Since increment 003 it also reports on itself: a third scheduled run at 07:00 that reads what the
two halves left behind and tells support about everything that is wrong.

## The two legs

```
ASB queue yotresultsdistribution.requests
   │  (peek-lock delivery)
   ▼
YotResultsDistributionMessageListener            inbound adapter — parse + settle ONLY
   ▼
DistributionPipeline                    application service — the use case, no I/O of its own
   ├─▶ IdempotencyGuard                 (source, requestId) processed-log — has this been done?
   ├─▶ HearingPayloadSource      «port» fetch hearing payload   (Redis INT_ keys → results-query fallback)
   ├─▶ NowSubscriptionsSource    «port» reference-data now-subscriptions for the register date
   ├─▶ RegisterTransformer       «port» fragment → subscription matching → the single aggregation
   ├─▶ RegisterDocumentValidator «port» the frozen schemas, enforced at the write (fix C29)
   └─▶ RegisterStore             «port» record the register document — NOT a POST
   ▼
ProcessingStateService                  writes processed_request / processed_output rows

──────────────────────────────  18:00 Europe/London, Mon–Fri  ──────────────────────────────

RegisterGenerationJob                   the scheduled run, one ShedLock-held run per night
   ├─▶ FeatureFlagGate                  reads YotResultsDistributionService once per run, no cache, fail-closed
   ├─▶ StaleBatchReleaser               the run's FIRST act: every PENDING or GENERATING batch past
   │                                    its cutoff is failed NOT_COMPLETED_BY_NEXT_RUN and its rows
   │                                    released, one fenced statement per batch
   ├─▶ BatchAssembler                   recorded rows → one batch per (court centre, register date)
   ├─▶ RegisterGenerationService        per batch: assemble payload, mint ids, request the render
   │      ├─▶ PayloadFileStore   «port» write the PDF payload into the platform file service
   │      └─▶ DocumentRenderer   «port» systemdocgenerator generate-document (202)
   └─▶ RunReport                        one structured line + three gauges, every run

DocumentEventListener                   inbound adapter on Artemis public.event — parse + drop ONLY
   ▼
DocumentOutcomeSink              «port» document-available / generation-failed → the batch
   ▼
RegisterNotifier                 «port» notificationnotify send-email-notification, one per YOT

──────────────────────────────  07:00 Europe/London, Mon–Fri  ──────────────────────────────

ExceptionReportJob                      the morning run, one ShedLock-held run per weekday, on a
   │                                    scheduler of its own — never behind the 18:00 run, and on
   │                                    no cutover circuit: it reads the flag nowhere
   ├─▶ ExceptionReportService           the eight reads over one window, oldest first
   └─▶ ExceptionReportSink       «port» every sink on the context, each asked whatever the last said
          ├─▶ LogEventReportSink        yotresultsdistribution_exception per entry + one summary per run
          └─▶ EmailReportSink           the list as a CSV, then one send per support address
                 ├─▶ PayloadFileStore   «port» the same write the 18:00 run makes, second caller
                 └─▶ ReportMailer «port» notificationnotify send-email-notification, one per address

IntakeAgeSweep                          its own fixed delay, in EVERY non-command JVM and under NO
BatchAgeSweep                           lock — the latter in every such JVM that carries the
                                        generation half, which is where its batches are. A gauge
                                        describes the JVM that publishes it, so an alert aggregates
                                        the replicas with max(). The second takes the three
                                        in-flight batch ages the release pass left with no reader:
                                        a Micrometer gauge never decays, so a reading nobody
                                        refreshes goes on looking live
```

- **Inbound adapters** (`YotResultsDistributionMessageListener`, `DocumentEventListener`) deserialise, and
  settle or drop. NO business logic. NO transformation. NO downstream calls. The queue listener
  performs exactly one settlement (`complete` / `abandon` / `deadLetter`) on every path; the topic
  listener acknowledges by returning and counts every drop under a bounded reason.
- **Application services** (`DistributionPipeline`, `RegisterGenerationService`,
  `DocumentOutcomeSinkImpl`, `RegisterNotifierService`, `ExceptionReportService`) orchestrate
  against **ports only**. They
  MUST NOT import Azure, Redis, JMS or HTTP client types, nor any other infrastructure wire type.
  Jackson is the one qualified exception: the hearing payload crosses the core as the platform
  Jackson generation's `JsonNode` by design (Principle IV, "canonical JSON in"), treated as
  immutable — read it, derive from it, never mutate a node the core did not construct. That
  permission covers `JsonNode` and its subtypes only; Jackson's binding, streaming and
  `ObjectMapper` configuration machinery stays in the adapters and in `config/`.
- **The job is application code too.** `RegisterGenerationJob` and `ExceptionReportJob` may hold a
  clock, a lock and ports; neither may hold a driver, a broker client or an HTTP client.
  `IntakeAgeSweep` holds a clock, a repository and the instruments, and no lock at all.
- **Ports:** Java interfaces owned by the application package. One port per external capability.
  Adapters implement them and live in their own package.
- **Adapters:** the only place infrastructure types appear. Stub adapters (logging no-ops behind the
  real port interfaces) are a legitimate transitional state, and both adapter modes default to
  `LIVE` — a service that has to be told to fetch payloads is one that will be deployed not
  fetching them. If swapping an adapter forces a pipeline edit, the port is wrong — fix the port,
  not the pipeline.
- **Persistence:** JdbcClient repositories, accessed only by `ProcessingStateService`,
  `IdempotencyGuard`, `JdbcRegisterStore`, `RegisterNotifierService`, `DocumentOutcomeSinkImpl`,
  `ExceptionReportService`, `IntakeAgeSweep` and `BatchListingService`. Never from a listener,
  never from a controller, never from the job directly. The last three are **readers and nothing
  else**: the report and the sweep take the eight report reads and the two gauge reads, and the two
  operations listings take the three reads they are built from — none of them writes a row.
- **The report is not on the cutover lever's circuit.** `ExceptionReportJob` reads the
  `YotResultsDistributionService` flag nowhere and is gated by it nowhere, and it runs whatever
  `yotresultsdistribution.generation.enabled` says — a pod that renders nothing still says every morning
  what is wrong with what it recorded. It is therefore not a second reader of the one lever.

NEVER put business logic in a message listener.
NEVER call a repository or an HTTP client from a listener.
NEVER reference `ServiceBusReceivedMessage` outside the queue's inbound adapter, or `TextMessage`
outside the topic's.

### Package structure

```
uk.gov.hmcts.cp.yotresultsdistribution
├── api/           the operations API: the seven controllers, their request and response records,
│                  the ProblemDetail advice, and the auth/audit filter wiring. An INBOUND ADAPTER —
│                  it parses, calls one application service, and maps the answer. No logic
├── inbound/       ServiceBusProcessorClient config, message listener, DistributionCommand parsing
├── application/   DistributionPipeline, RegisterGenerationService, DocumentOutcomeSinkImpl,
│                  RegisterNotifierService, ExceptionReportService, IdempotencyGuard,
│                  ProcessingStateService, and the fourteen port interfaces (RegisterStore,
│                  DocumentRenderer, PayloadFileStore, RegisterNotifier, DocumentOutcomeSink,
│                  FeatureFlagReader, RenderProgress, ExceptionReportSink, ReportMailer, …)
├── domain/        records + enums (DistributionCommand, RequestStatus, BatchStatus,
│                  BatchFailureReason, NotificationStatus, CompletionReason, RegisterBatch, …)
├── adapter/
│   ├── stub/               logging no-op implementations (test/local profiles only)
│   ├── payload/            Redis + results-query-api hearing payload source
│   ├── refdata/            reference-data now-subscriptions client
│   ├── fileservice/        the framework file-service metadata + content write (pinned 001–006)
│   ├── systemdocgenerator/ generate-document command client
│   ├── notificationnotify/ send-email-notification command client
│   ├── publicevents/       the Artemis public.event listener and envelope parsing
│   ├── appconfig/          the Azure App Configuration flag reader, authorised by the estate's
│   │                       shared connection string from Key Vault - never workload identity
│   ├── report/             the exception report's two sinks: the structured events, and the
│   │                       CSV-and-e-mail one
│   ├── http/               the shared HTTP concerns the four clients above sit on
│   └── progression/        the 001 add-court-register client, retained for progression-post mode
├── batch/         RegisterGenerationJob, BatchAssembler, FeatureFlagGate, StaleBatchReleaser,
│                  RecipientSet, ExceptionReportJob, IntakeAgeSweep, BatchAgeSweep
├── pipeline/      ported transformation: RegisterBuilder, SubscriptionMatcher, AggregationMapper
├── persistence/   repositories; Flyway migrations in src/main/resources/db/migration
└── config/        typed @ConfigurationProperties, ObjectMapper, health indicators, the two
                   metrics classes, and the auth/audit starter settings
```

## Domain Model

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`, optional `userId` |
| `ProcessedRequest` | entity | PK `(source, requestId)`; `hearingId`, `hearingDay`, `eventType`, `status`, `attempts`, `completionReason`, `failureReason`, claim triple, timestamps |
| `ProcessedOutput` | entity | PK `outputId`; FK `(source, requestId)`; `courtCentreId`, `courtCentreOuCode`, `registerDate`, `fileName`, `requestDigest`, `status`, `responseCode`, `postedAt`; **UNIQUE `(source, requestId)`** |
| `RegisterFragment` | record | **One per hearing** — the register defendants after court-extract filtering, court centre id/OU code, the three dates, matched subscriptions |
| `CourtRegisterDocument` | record | The register document: hearing venue, recipients, **youth defendants only**, validated against the vendored schemas **before the write into the store** (fix C29) |
| `RegisterRecord` | entity | One recorded register document, keyed to its hearing, carrying its court centre day and its `shared_time`; the unit the nightly job batches |
| `RegisterBatch` | entity | One (court centre, register date) batch: `batchId`, `status`, `payloadFileId`, `documentFileId`, `requestedAt`, `generatedAt`, `failureReason`, `sdgReason`, per-recipient notification rows |

Inbound is **JsonNode-canonical**: the hearing payload stays a Jackson tree and is read through a
typed facade; only what this service *produces* is modelled as typed records. Do not write a full
typed model of the hearing.

**The three dates are distinct and MUST NOT be conflated** (design §2.2): the command's `hearingDay`
keys the Redis claim-check and nothing else; the fragment's `hearingDate` derives from the latest
`judicialResult.orderedDate`; `registerDate` derives from `sharedTime` and drives the reference-data
`on=` day, the batching key, and the filename.

## Processing State Machine — the intake leg

Every command reaches an explicit recorded outcome. "Nothing happened" is never an acceptable end
state — silent failure is the disease this service exists to cure.

```
message received
   ▼
(source, requestId) already COMPLETED? ── yes ─▶ log + complete()  [no re-record, no state change]
(source, requestId) already FAILED?    ── yes ─▶ FAILED → RECEIVED [audit note, attempts preserved]
   │ no
   ▼ INSERT processed_request status=RECEIVED, attempts=1
   ▼ fetch payload            (port)   both cache and fallback miss ⇒ TRANSIENT (fix C32)
   ├─ isGroupProceedings ─────────────▶ COMPLETED, completion_reason=group-proceedings (fix C7)
   ▼ build fragment           (port)
   ├─ no register defendants ─────────▶ COMPLETED, completion_reason=no-defendants
   ▼ match subscriptions      (port)   refdata unanswered ⇒ TRANSIENT, never an empty register
   ├─ none matched ───────────────────▶ COMPLETED, completion_reason=no-subscriptions
   ▼ youth filter + map aggregation
   ├─ no youth defendants ────────────▶ COMPLETED, completion_reason=no-youth-defendants
   ▼ validate against vendored schema (fix C29)  invalid ⇒ FAILED + deadLetter, reason recorded
   ▼ record once              (port) → the register store, superseding any earlier row for the
   │                                    hearing at the write
   ├─ recorded ───────────────────────▶ COMPLETED, completion_reason=recorded ─▶ complete()
   ├─ transient failure ──────────────▶ RETRYING, attempts++ ─▶ abandon()  → ASB redelivers
   └─ non-transient failure ──────────▶ FAILED + reason ─▶ deadLetter() → DLQ alert
```

Statuses — request level: `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`.
Completion reasons: `recorded`, `group-proceedings`, `no-defendants`, `no-subscriptions`,
`no-youth-defendants` — plus `submitted`, which only `yotresultsdistribution.output=progression-post`
produces.

Rules:

- `COMPLETED` and `FAILED` are **terminal**, and are not treated alike on a resubmission:
  - `COMPLETED` — acknowledged and `complete()`d without reprocessing.
  - `FAILED` — **replayable**. A resubmitted message (fresh broker `messageId`, same `requestId`)
    makes the guard transition `FAILED` → `RECEIVED`, preserving `attempts` and writing an audit
    note, then reprocess. This is the supported way to recover a dead-lettered request.
  - Ordinary broker redelivery of the *same* message is unaffected.
- The four no-op outcomes are business outcomes, not errors — recorded, bounded, and mutually
  distinguishable (fix C33). Two of them are this flow's **most common** results.
- **Transient** (retry, `abandon()`): connection/IO errors, HTTP 5xx, 429 and 408 (honour bounded
  delta-seconds `Retry-After` — fix C3), payload source unavailable, reference data unanswered,
  register store unavailable.
- **Non-transient** (`FAILED`, `deadLetter()`): unparseable message, schema violation (inbound or
  outbound — fix C29), transformation errors (C19/C20/C21 keep the throw and report it).
- Every state transition is persisted **before** the message is settled. Settle last.

## Batch State Machine — the generation leg

Every batch reaches an explicit terminal state too, and a night that generated nothing says which
kind of nothing it was.

```
recorded rows, active and unbatched
   ▼ (18:00 run, flag ON) BatchAssembler groups by (court centre, register date)
   │  a court centre day whose batch is still in flight is DEFERRED — no batch, no document tonight
   ▼ PENDING            the batch exists and holds its rows
   ▼ assemble payload → PayloadFileStore  ⇒ failure: FAILED/ASSEMBLY_FAILED
   │                                          or FAILED/PAYLOAD_STORE_UNAVAILABLE
   ▼ mint ids, then request the render (ids before calls, always)
   ▼ GENERATING         systemdocgenerator accepted (202)
   │     ├─ refused / undeliverable ─▶ FAILED, RENDER_REQUEST_REJECTED / RENDER_REQUEST_FAILED
   │     ├─ document-available (public.event) ─▶ GENERATED
   │     ├─ generation-failed  (public.event) ─▶ FAILED, GENERATION_FAILED (+ sdg_reason)
   │     └─ neither, and still so at the next run ─▶ FAILED, NOT_COMPLETED_BY_NEXT_RUN,
   │                                                 rows released into that run's batches
   ▼ GENERATED          the PDF exists in the file service
   ▼ notify every matched Youth Offending Team, once each
   ├─ all accepted ──────────────────▶ NOTIFIED
   ├─ some accepted ─────────────────▶ PARTIALLY_NOTIFIED   (the rest are resendable)
   └─ nobody to tell ────────────────▶ NOTIFIED_NOBODY
```

Statuses — batch level: `PENDING`, `GENERATING`, `GENERATED`, `NOTIFIED`, `PARTIALLY_NOTIFIED`,
`NOTIFIED_NOBODY`, `FAILED`.
Failure reasons (bounded): `ASSEMBLY_FAILED`, `PAYLOAD_STORE_UNAVAILABLE`, `RENDER_REQUEST_FAILED`,
`RENDER_REQUEST_REJECTED`, `GENERATION_FAILED`, `NOT_COMPLETED_BY_NEXT_RUN`.
Per-recipient notification statuses: `PENDING`, `ACCEPTED`, `FAILED`.

Rules:

- **Ids before calls.** The payload file id and the batch id are minted and written down *before*
  the render is requested, so an outcome that arrives can always be correlated. A call made before
  its id is recorded is an outcome nothing can be applied to.
- **A failed batch releases its rows.** `releaseFailed` puts the registers back for the next run;
  a failure must never leave a register stranded in a dead batch.
- **Nothing is invented about a batch nothing was learned about.** Such a batch is failed by the
  next run, through the store rather than through the sink, with its rows released — no outcome is
  applied, because there is no outcome, and applying one would be inventing evidence.
- **A late or duplicate outcome moves nothing** and is counted: a team that has been told has been
  told. Every acknowledged-and-dropped path on the subscription carries a bounded reason on
  `yotresultsdistribution_public_events_ignored_total`.
- **The run report is not a tally of the night.** `generated` and `notified` are a snapshot taken at
  the moment the line is written, of a night that may still be settling; `snapshot=taken|unread`
  says whether they were read at all, and an unread snapshot is counted on
  `yotresultsdistribution_generation_unrecorded_total`.
- Every batch transition is persisted before the message or event that caused it is settled or
  acknowledged.

## The Cutover Rule — one lever

The whole flow is switched between the legacy implementation and this service by **one Azure App
Configuration feature flag, `YotResultsDistributionService`**.

- The nightly job reads it **once per run, with no cache**, and does nothing when it is off or
  unreadable. Fail-closed: every failure to read leaves the legacy in charge.
- **Never add a second switch** — no Helm value, no static-data patch, no endpoint — that decides
  which implementation is live. `yotresultsdistribution.output` and `yotresultsdistribution.generation.enabled` are
  deployment shape, not cutover levers, and neither may be documented as one.
- **An operations endpoint is not a second lever.** `POST /operations/batches/generate` reads the
  same flag, through the same `FeatureFlagGate`, at exactly the point `generate-register` read it,
  and refuses `FLAG_OFF` unless the body carries `ignoreFlag: true` — which is the same decision
  `--ignore-flag` was, taken by a named caller instead of by whoever held exec rights. The override
  is recorded in the audit event and printed on the run report, exactly as the command counted it.
  An endpoint that *decided which implementation is live*, or that could run the generation leg
  without the flag having been read at all, **would** be a second lever and is forbidden.
- Never run generation with notification enabled against production data outside cutover.

## Queue and Topic Semantics

Queue **`yotresultsdistribution.requests`** (+ its dead-letter queue), owned by this service.

- **Peek-lock only.** `ReceiveAndDelete` is banned — it loses messages on crash.
- **Auto-complete disabled.** Exactly one explicit `complete()`, `abandon()` or `deadLetter()` on
  every path. A path that can return without settling is a bug; reviewers reject it.
- `maxDeliveryCount` = **5**; the broker's `getDeliveryCount()` is 0-based, so the final permitted
  delivery carries count 4.
- **Broker duplicate detection is ON.** `messageId` = `"{source}:{requestId}"`. The service must not
  depend on it alone — the `(source, requestId)` processed-log is the real guard.
- **Replay tooling always mints a fresh `messageId`** and keeps the original `requestId`.
- `maxConcurrentCalls` starts at **2**. Raise only once golden tests prove the pipeline is
  stateless.
- **ASB health MUST NEVER gate readiness.** A broker blip must not restart the pod.

Topic **`public.event`** on Artemis, the estate's shared topic, consumed through a **shared durable
subscription that every replica attaches to**.

- The subscription is a filter, not a guarantee: every context publishes here, and
  systemdocgenerator announces every document it renders for anybody. Three things must hold before
  an outcome touches a batch — the message says what it is (envelope, not just the `CPPNAME`
  header), the document is this service's (`originatingSource`), and the outcome names a batch this
  service recorded.
- **Acknowledge and drop, never nack.** A durable subscription offers a nacked message again for
  ever, and a foreign document is never going to become ours.
- Every drop is counted under a bounded reason — including `terminal-batch`, the outcome that
  arrives for a batch this service had already ended, and `incomplete-outcome`. Nothing of an
  unreadable body reaches the log or a label.
- **Shared, and therefore scalable.** A non-shared durable subscription admits exactly one
  consumer: a second pod is refused by the broker and retries at ERROR for ever. The subscription is
  shared and keyed by its name alone — **no client id**, because a client id every replica carried is
  what the broker refuses the second connection for. Concurrency stays at one consumer per pod.
  Changing a subscription between shared and non-shared abandons the existing subscription and its
  backlog, so it is a broker-visible change and not a local edit.
- Every JVM that runs this application subscribes, and there is no longer any other kind: the CLI
  JVM the `yotresultsdistribution.cli` rule used to keep off the topic no longer exists, because an
  operations call is served by a pod that is already subscribed rather than by a process about to
  exit. An operations endpoint must never bring up a second subscription of its own.

## The operations API

Seven endpoints under `/operations/**`, one per action an operator used to reach by
`kubectl exec`. They are an **inbound adapter** in `api/`: each parses its request, calls the one
application service the CLI class called, and maps the answer. No transformation, no repository
call, no HTTP client, no business decision.

```
HTTP request  (CJSCPPUID header)
   ▼
cp-auth-rules-filter     drools rules in src/main/resources/acl/operations-rules.drl
   │                     one rule per action, "Second Line Support" only; denied ⇒ refused here
   ▼
cp-audit-filter-springboot   every request and response published to the audit context
   ▼
api/*Controller          inbound adapter — parse, call, map. NOTHING else
   ▼
the same application services the CLI called:
   FeatureFlagReader · FeatureFlagGate + BatchAssembler + RegisterGenerationService ·
   RegisterNotifierService · RegisterStore · RegisterBatchRepository +
   RegisterNotificationRepository · ExceptionReportService + its sinks
```

- **One endpoint per action, and no capability the CLI did not have** — with three exceptions, each
  of which makes an endpoint *stricter* than its command because HTTP reaches further than
  `kubectl exec` did: supersede is admitted only while the flag says OFF and gains a `dryRun` and an
  age bound; regeneration takes the nightly lock instead of trusting a runbook; and both are
  audited. Otherwise: the same arguments, the same refusals, the same fields — as JSON rather than
  as `key=value` lines.
- **The three exit codes become a status map, refined where HTTP has a truer code.** `400` a
  malformed or missing argument; `404` a well-formed identifier that names nothing; `409` a state
  refusal that changed nothing; `503` a dependency this endpoint exists to read or write that is
  unavailable; `502`/`504` a downstream platform contract that refused or did not answer; `500` an
  unexpected defect **and nothing else** — a 500 this service can explain is a 409, a 503 or a 502
  it failed to classify, and the one it cannot is answered `UNEXPECTED` by the advice's single
  fallback rather than left to the container, because the audit filter publishes no response event
  for an exception that leaves the dispatcher. Every non-2xx answer is a `ProblemDetail` carrying a bounded `reason`.
  One status belongs to the surface rather than to an action: `415 UNSUPPORTED_CONTENT_TYPE`, for a
  `Content-Type` beginning `multipart/`, which `cp-audit-filter-springboot` hands down the chain
  without publishing either of its events. It is refused by `OperationsActionFilter` ahead of both
  estate filters, because an endpoint reachable unaudited is an endpoint that may not exist.
- **One endpoint is asynchronous, and it is the dangerous one.** Regeneration answers
  `202` with a run id and does the work on the generation scheduler's single thread, because the
  CLI's inline render requests ran under a sixty-minute deadline and no gateway will hold a
  connection that long. Everything else answers when it is done.
- **Nothing the caller typed is echoed back**, in the body or in a log line: a refusal names the
  *argument*, never the value (Principle VII). Recipient addresses are masked exactly as
  `list-batches` masked them. No exception text, no store's or far end's own words.
- **The flag is read where the command read it, or more strictly, never more loosely.**
  `check-flag`'s endpoint reads it because that is what it is for; the generate endpoint reads it
  through the same `FeatureFlagGate` and takes `ignoreFlag` as the per-request break-glass
  `--ignore-flag` was; **supersede reads it although its command did not**, and is admitted only
  while it says OFF, with no override and fail-closed on unreadable — an unconditional HTTP mutation
  that gives a period of registers up is a second lever however well authorised; the
  exception-report endpoint reads it **nowhere**, as `report-exceptions` did not.
- **The 18:00 lock is taken, not asked about, and this is the one rule the CLI did not have.** The
  CLI left "do not regenerate during the nightly run" to a runbook. Asking whether the lock is held
  and then acting races the scheduler; the background regeneration takes the same
  `@SchedulerLock` name by a non-blocking attempt and records the refusal as the run's outcome when
  it cannot.
- **The claims that already exist do the arbitrating.** Two concurrent notifies for one batch are
  decided by `RegisterNotifierService`'s claim and its four dispositions, not by anything new; two
  concurrent regenerations for one date are decided by `releaseFailed` returning no rows to the
  loser and by the live-key index refusing its assemble — which must surface as a clean bounded
  refusal, never a 500.
- **`@ControllerAdvice` and `ProblemDetail` are permitted here and nowhere else.** The message
  listeners and the jobs still convert an exception into a settlement or a persisted state, never
  into a response.
- **Both filters are on by default, and the pod always starts.** `authz.http.enabled` and
  `audit.http.enabled` read `true` in `application.yaml` against library defaults of off, so a
  deployment that says nothing is authorised (FR-045, constitution 5.0.1). They are
  ordinary configuration: an operator may turn either off, the compose environment and the `test`
  profile do exactly that with the reason written beside them, and **start-up never refuses on the
  combination** — no cross-field rule against `yotresultsdistribution.operations.enabled`, no
  `yotresultsdistribution.servicebus.namespace` discriminator, no laptop-versus-pod exemption. What is still
  refused is a **value** that cannot mean what it says (FR-053): an audit transport switched on with
  no host or a port outside 1..65535, an audit filter switched on with no OpenAPI document to
  resolve, and an unusable `supersede-max-age` or `lock-wait`.
- **Being audited takes the transport key as well, and that one is the deployment's.**
  `audit.http.enabled` builds nothing on its own: every `audit.http.*` bean the starter declares
  sits inside the `@AutoConfiguration` class `cp.audit.enabled` gates, and this service ships
  `cp.audit.enabled: ${CP_AUDIT_ENABLED:false}` so a laptop with no audit broker starts. Condition
  (b) is therefore met by a deployed values file setting `CP_AUDIT_ENABLED=true` with the broker's
  connection from Key Vault — a values file without it serves the operations API **unaudited**.
  Nothing refuses that combination, so the pod **says** it: one WARN at start-up naming both
  settings, because the filter that would have published was never constructed and the one that is
  swallows its own publishing failures.
- **Actuator is not part of this surface** and is not behind these filters.

## Idempotency and Supersession

The register document is written into **this service's own store**, so the 001 argument about
progression absorbing a duplicate POST no longer applies. What replaces it:

- `processed_request` — PK `(source, request_id)`. The insert is the claim; a unique-violation means
  a concurrent delivery is already processing and is handled, not logged as an error.
- **Supersession happens at the write.** A re-share of the same hearing supersedes the earlier
  recorded register rather than appending a second one — `V3__active_row_unique.sql` is what makes
  "one active register per hearing" a constraint rather than a convention.
- `request_digest` (SHA-256 of the document) is written before the write and left in place after a
  failure — what was attempted is the evidence.
- An **ambiguous write** is retried: prefer a possible duplicate, which supersession absorbs, over a
  possible loss, which is silent.
- Migrations are **Flyway** (`V<n>__<snake_case_description>.sql`) — never Liquibase. Additive and
  forward-only; never edit an applied migration.
- Hearing payloads are **never persisted**. Redis and the results query API remain the source.
- The log doubles as the support answer to "was this hearing processed, and did its register go
  out?" — keep it queryable by `hearing_id`, `hearing_day` and batch.

## The Consumed Platform Contracts

This service **adapts to** four contracts it does not own, and never redefines them (Principle III):

| Contract | Owner | What this service may not do |
|---|---|---|
| systemdocgenerator `generate-document` (REST, 202) + the `document-available` / `generation-failed` public events | systemdocgenerator | Add a field, treat any 2xx but 202 as success, or infer an outcome no event carried |
| notificationnotify `send-email-notification` (REST, 202) | notificationnotify | Batch recipients into one call, or retry a 4xx |
| the framework file-service `metadata` + `content` table schema (write-only, pinned to changesets 001–006) | the framework | Read through it, or migrate it. **The file service is the only store outside this service's own that may be written directly** (design owner, 2026-09-14, closing design Q20): no other context's tables are ever written. Two callers write through it since 003, not one - the nightly run's render payload and the morning report's exception CSV - through the same pinned changesets and the same write-only port |
| the `YotResultsDistributionService` App Configuration flag | the cutover | Cache it, default it open, or add a second reader with different semantics |

Plus the two this service's own increments froze: the **inbound queue message**
(`distribution-command.schema.json`, `additionalProperties: false`, agreed with
`cpp-context-results`) and the **register document** (`courtRegisterDocument/*` at
`criminal-court-public-model` 17.103.13, vendored, enforced at the write into the store).

A change to any of these is a cross-team event, not a local edit.

## Fix-First and the Defect-Fix Register

This port fixes every catalogued defect and keeps legacy behaviour everywhere else. Since 002 the
register has **two oracles**: the JavaScript function app for the intake half, and progression's
court-register leg for the downstream half. The register is `doc/DEFECT-FIXES.md` (Principle I).

- Every fix MUST have a register row: defect ref, legacy behaviour (`file:line`), fixed behaviour,
  rationale/impact, **the pinning test**, sign-off status. A fix without a row is reverted.
- `C` rows are the function-app catalogue (C1–C34 from the design, plus anything appended under
  review); `P` rows are progression's leg. An appended row carries the same obligations as an
  original one.
- Fixes that change business-visible content carry a **sign-off-before-cutover** marker.
- Do NOT fix behaviour that is not on the register, however wrong it looks. An uncatalogued change
  needs written sign-off first — either it earns a number (append-only) or the legacy stands.
- Golden files encode **fixed** behaviour; a golden changes only in the same commit as a
  DEFECT-FIXES entry.
- The differential audit is an assertion on every build, in both directions: a catalogued defect
  still reproduced, or a difference that maps to no row, is build-blocking.
- Behaviours deliberately KEPT and easy to mistake for defects: the group-proceedings **skip
  itself** (a business rule — C7 fixes its type-handling and its silence); the `####` join replaced
  by a real newline (C24); letter-delivery subscriptions still email-only (C27 fixes the *silence*
  of the drop, not the drop); the batch header taken from any member of the batch (progression's
  `stream().findAny()`, reproduced deliberately and noted in the goldens' provenance).

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block. Catch to classify and rethrow, or to map onto
  a state that is persisted and settled explicitly.
- **The one absorbed refusal** is telemetry: a round-trip reading that cannot be taken may not cost
  a Youth Offending Team its e-mail, so it stops where it happens, is counted, and is said at WARN.
  It is the rule for both sweeps — `IntakeAgeSweep`'s two gauges and `BatchAgeSweep`'s three, each
  counted on its own series. Every other refusal still leaves.
- **Never attach a throwable this service did not write.** A caught exception is named by **class**;
  its message belongs to whatever library raised it and is exactly where a connection string or a
  fragment of a statement turns up. The log-statement sweep enforces this, and it governs INFO and
  above.
- Every log line carries the correlation of the unit of work it belongs to: `requestId` and
  `hearingId` (MDC) on the intake leg, `runId` for a scheduled run, and the batch id for a line
  about one batch. `source` and court-centre id/OU code where relevant. A scheduled run has no
  delivery identifiers and never can, which is why it has one of its own (Principle VII, v3.1.0);
  `batch/RunCorrelation` opens it, adopts an ambient one where a run reached into a sweep, and only
  whoever opened it clears it - the scheduler's threads are pooled.
- **No defendant PII at `info`** — no names, addresses, dates of birth, ASNs, or URNs. Identifiers
  only. Every defendant on this register is a **youth**. PII-bearing detail belongs at `debug` and
  must be off in deployed environments.
- `completion_reason`, `failure_reason` and every metric label are bounded codes — never raw
  exception text, never a fragment of a message body, never a court centre id or an e-mail address
  as a label (cardinality *and* privacy).
- A path that drops something must move a counter. "It is in the log index" is not an alerting
  surface.

## Out of Scope — do not build here

- Any **business** REST API — a hearing submitted over HTTP, a register read out over HTTP, a batch
  created by a caller, a status or replay surface. The operations API is the named operator actions
  and nothing else; a path that is not one of them needs a constitution amendment, not a spec
  (Principle III). Widening an existing endpoint into a query surface is the same thing by degrees.
- The prison court register — its own pipeline, its own future migration. Keep the seams clean; the
  shared kernel this port produces is what the PCR migration will consume.
- SJP hearings — the court register has no SJP leg at all (unlike informant).
- Any change to the register document's shape, or to the four consumed platform contracts.
- The legacy function-app repo, the results producer and progression's retirement PR. C18a, C28,
  C34 (legacy repo), C18b (the producer's flag-gated publisher in `cpp-context-results`) and P6,
  P7 (progression's deletions) are registered items owned elsewhere and tracked to conclusion
  before cutover.
