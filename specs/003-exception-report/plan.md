# Implementation Plan: Exception report for production support

**Branch**: `003-exception-report` | **Date**: 2026-09-14 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-exception-report/spec.md`

## Summary

Turn the record increments 001 and 002 built into something support reads. A new
`ExceptionReportService` builds a typed `ExceptionReport` over a reporting window from the
repositories alone (no downstream call, no write), containing five bounded kinds of exception:
requests that reached FAILED inside the window, requests still RECEIVED or RETRYING past the intake
threshold, batches past a stage limit (awaiting render, generated but not notified, or a recorded
register the most recent scheduled generation run left unbatched), batches that reached FAILED
inside the window (carrying their bounded `BatchFailureReason` and never systemdocgenerator's own
`sdg_reason`, which is another system's free text), and notifications that reached FAILED inside the
window. The service has two methods, not one: `build(ReportWindow, String runId)` reads, and
`deliver(ExceptionReport, Collection<ExceptionReportSink>)` hands the built report to the sinks the
**caller** chose - every sink on the context for the job, the log sink always and the e-mail sink
only under `--email` for the command. Two sinks ship:
`LogEventReportSink` writes one `yotresultsdistribution_exception_report` summary event - **twelve fields
since increment 004 (amended 2026-09-21): the run id, the three instants, one count per kind and
what the entry cap dropped, and no delivery
status, because a sink cannot observe its own delivery or the other sink's** - and one `yotresultsdistribution_exception` event per
exception, as structured fields the platform's container-log collection carries into Log Analytics.
`EmailReportSink` writes the exception list as a CSV into the framework file service and asks a
second new port, `ReportMailer`, for one `send-email-notification` per configured recipient with
that file attached by id; its adapter, `NotificationNotifyReportMailer`, shares
`NotificationNotifyClient`'s HTTP shape through a package-private request builder, so the register
path's own body and tests are untouched. A sink failure is classified, recorded as a
`DeliveryOutcome` with a bounded reason, counted, and never stops the other sink.

**How the report travelled is the run's fact, not a sink's.** After every sink has returned,
`ExceptionReportJob` writes one flat run line of its own -
`event=exception_report_run run_id=... window_from=... window_to=... entries=N
delivered_log=ok|failed delivered_email=ok|failed|disabled outcome=delivered|partial|failed
duration_ms=...` - exactly as `RegisterGenerationJob.recorded` writes `register_generation_run`
after a night. `ReportExceptionsCli` prints the equivalent as its last line, with a fifth value the
job never writes: `skipped`, for a sink this deployment has and this invocation chose not to ask.

`ExceptionReportJob` runs the report at **07:00 Europe/London Mon-Fri** under ShedLock, independently
of `yotresultsdistribution.generation.enabled`, over the window that begins at the **previous scheduled run**
of that same cron - so a Monday run reads back to Friday and every FAILED request, batch and
notification lands in exactly one report, with no window setting that could disagree with the
schedule. `IntakeAgeSweep` refreshes the two intake gauges the design promised and 001 never built -
the age of the oldest unfinished request and the count of unfinished requests over the threshold -
on its own fixed delay in **every** non-command JVM and under **no** lock, because a gauge describes
the JVM that publishes it; an alert therefore fires within one refresh interval rather than the next
morning, aggregating across pods with `max()`. A request-duration timer tagged by terminal outcome
completes the four instruments of design section 11. A sixth operations command,
`report-exceptions`, produces the same report on demand for a window given as an instant or a
duration, optionally e-mailing it. Every threshold, the schedule, the gauge-refresh interval, the
recipients and the template are settings with documented defaults and startup refusals.

Nothing about the register document, the inbound message, or the four consumed platform contracts
changes. Two of those contracts (the file service and notificationnotify) are used exactly as 002
uses them, from the artefacts 002 vendored.

Design authority: Confluence *Court Register Service* (CRA) section 11 (the four promised
instruments) and the currency review of 2026-09-14 that recorded the gap.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle wrapper; unchanged from 001 and 002).

**Primary Dependencies**: **none added.** Everything this increment needs is already on the
classpath: Micrometer for the three counters, two gauges and the timer; ShedLock
(`shedlock-spring` + `shedlock-provider-jdbc-template`) for the **one** new lock, the report's -
the sweep takes none, by design; Spring's `@Scheduled` (whose `scheduler` attribute, added in
Spring Framework 6.1, is present on this classpath: `javap -v` over
`org/springframework/scheduling/annotation/Scheduled.class` in the `spring-context-7.0.9.jar` this
Boot 4.1.1 BOM resolves lists `public abstract java.lang.String scheduler();`) and `CronExpression`;
`JdbcClient` for the nine repository reads and the file-service text write; `RestClient` behind the existing `NotificationNotifyClient` shape; and
`net.logstash.logback` (already the only encoder `logback.xml` uses), whose
`StructuredArguments.value(...)` is what turns the two report events into queryable fields.
Test-side: JUnit Jupiter, Mockito, AssertJ, WireMock, Testcontainers Postgres, all present.

**Storage**: PostgreSQL 16. One additive migration, `V4__processed_request_report_indexes.sql`, which
adds two indexes and no columns and no tables (data-model.md). The second, write-only file-service
`DataSource` that 002 introduced gains one more caller: `PayloadFileStore.storeText`, the CSV
attachment, written with the same two inserts in the same order as `store`.

**Testing**: as 002 (JUnit Jupiter 6, Mockito, AssertJ; `*Test` no Docker, `*IT` Docker) plus:
`ApplicationContextRunner` for the property refusals; `SimpleMeterRegistry` for the instrument
surface; a captured log (`support/CapturedLog`) for the log sink's fields; WireMock for the
**mailer**, validating the body against the `notificationnotify.email.json` schema **002 vendored**
(the e-mail sink itself holds the `ReportMailer` port and needs no HTTP, so its own suite is a unit
one); Testcontainers Postgres for the V4 migration and the report's nine reads; the file-service
Testcontainers fixture for `storeText`, where `FileServicePayloadStoreIT` asserts exactly two
statements and `EmailReportSinkStoreIT` pins the CSV's own composition, so neither restates the
other; and one end-to-end `ExceptionReportEndToEndIT` that seeds one exception of each kind, reads
both the events and the e-mail back, and carries the SC-001, SC-006 and SC-008 cases.

**Target Platform**: AKS, port 4550 (local 8082). The report half is orthogonal to the generation
half: a pod with `yotresultsdistribution.generation.enabled=false` still runs the report. The sweep is
orthogonal to **both**: it refreshes the intake gauges in every JVM that is not a command, whichever
halves are switched on, because the instruments describe the intake half and the intake half is
always there.

**Project Type**: single Spring Boot service, actuator-only HTTP surface, CLI in the image.

**Performance Goals**: SC-006 - the on-demand command answers a `--since 24h` window within ten seconds
over a processed log holding one week of production-scale data (~1,100 requests per stack per week).
The two indexes V4 adds are what make the non-terminal and failed-since reads index-only rather than
sequential. The sweep's own read is bounded by the partial index rather than by a timeout: it is a
`LIMIT 1` and a count over the handful of rows still in flight, and it holds no lock whose budget
could be exceeded. SC-008 - the 07:00 run and the fixed-delay sweep never share a thread with the
18:00 run or the reconciler, and the report's lock is its own. **That claim rests on each scheduled
method naming its scheduler**, not on the three `TaskScheduler` beans existing: three beans on one
context route nothing by themselves, and Spring resolves a single scheduler for `@Scheduled` unless
told otherwise. All four scheduled methods therefore carry `@Scheduled(scheduler = ...)` naming the
bean their own configuration declares - the two generation surfaces keep sharing
`SchedulingConfig.GENERATION_SCHEDULER`, which is a new public constant naming the **existing**
`registerGenerationScheduler` bean and adds no bean - and four reflection cases pin the attribute,
because an attribute nobody asserts is one a later edit removes.

**Constraints**: constitution v3.1.0 - zone `Europe/London` validated at startup by the same rule
the generation schedule carries; one explicit settlement is not in play here (this increment settles
no message); ids before calls (the CSV's `fileId` is minted and written to the run's log line before
the file-service write); 202 and nothing else from notificationnotify; no PII at INFO or above and
recipient addresses never selected, and masked in the one place the e-mail sink holds one; bounded metric labels and bounded reason codes; `runId` on
every line of a scheduled run (Principle VII, v3.1.0); no throwable this service did not write
attached to a line; no AI attribution; TDD red-run convention.

**Scale/Scope**: five user stories; out of scope per spec - alert rules, dashboards and saved
queries (the observability waiver, landing with the operability story), any REST surface, any change
to the register document or the consumed contracts, queue and dead-letter depth (read from Azure
Monitor's own queue metrics), and retention of past reports.

### Configuration (this increment)

All under `yotresultsdistribution.report`, bound by `config/ReportProperties` and duplicated in
`application.yaml` with a comment per key saying what breaks without it, following the
`yotresultsdistribution.generation` block - **except the gauge-refresh interval**, which is the intake half's
and lives at `yotresultsdistribution.intake.gauge-refresh` on `YotResultsDistributionProperties`, because the sweep
runs where the report is switched off.

| Property | Local default | Purpose |
|---|---|---|
| `yotresultsdistribution.report.enabled` | `false` locally, `true` deployed | Master switch for the 07:00 job and the report scheduler. **Not a cutover lever**: the report reads and writes nothing the cutover decides, and it never reads the `YotResultsDistributionService` flag |
| `yotresultsdistribution.report.cron` / `.zone` / `.zone-override-acknowledged` | `0 0 7 * * MON-FRI` / `Europe/London` / `false` | The schedule, in Spring's six-field dialect. The zone rule is **identical to generation's**, and deliberately the same rule rather than a similar one: 07:00 is a wall-clock requirement in BST and GMT alike, so startup refuses any other zone unless the override is acknowledged |
| `yotresultsdistribution.report.lock-at-most-for` | `15m` | How long the report job's ShedLock lock is held. The job's `@SchedulerLock` reads this key as a **placeholder**, exactly as `RegisterGenerationJob.LOCK_AT_MOST_FOR` reads its own, so the duration is written once. Startup refuses any value below `PropertiesValidator.REPORT_RUN_BUDGET` (5m, fixed) plus the **existing** `PropertiesValidator.SCHEDULER_LOCK_MARGIN` (10m), which is what the shipped 15m is - the same margin generation's 60m + 10m = 70m already uses, reused rather than duplicated under a second name |
| `yotresultsdistribution.report.request-terminal-within` | `30m` | The intake threshold. A request still RECEIVED or RETRYING for longer than this is `REQUEST_LATE`, and is counted on `yotresultsdistribution_non_terminal_requests_over_threshold` |
| `yotresultsdistribution.report.batch-generated-within` | **unset, resolved to** `yotresultsdistribution.generation.grace-period` (`10m`) | The rendering limit. A batch PENDING or GENERATING for longer is `BATCH_LATE`. Defaults to the grace period because that is already the interval after which the reconciler decides a render has not happened, and two different answers to "how long is too long for a render" is the shape that makes an alert argue with a batch state |
| `yotresultsdistribution.report.notified-within` | `15m` | The notification limit. A batch GENERATED but not NOTIFIED for longer is `BATCH_LATE` |
| `yotresultsdistribution.report.max-entries` | `5000` | How many **`REQUEST_LATE` and `BATCH_LATE`** entries one report may carry. Over it the **oldest** are kept and the rest are counted as `truncated` on the summary event and on both run lines. It bounds the recurring late kinds only: a failure is never dropped, because the failure kinds are read over a half-open window no later run reads again, so a failure a window dropped is a failure no window would ever report again. The five counts are **not** capped either, because a count that shrank with the list would make the worst morning of the year read as a quieter one. A zero is refused at startup: a report that carries no late entry is indistinguishable from a morning with nothing wrong on it |
| `yotresultsdistribution.intake.gauge-refresh` | `10m` (its own `@DefaultValue`) | How often `IntakeAgeSweep` refreshes the two intake gauges. **Not** under `yotresultsdistribution.report`, and **not** resolved from the generation grace period: the sweep belongs to the intake half and runs where both other halves are switched off (FR-008, user story 2 scenario 4), so its interval must be readable on a pod that binds neither of the other two records. One literal default, read as a placeholder by `@Scheduled(fixedDelayString = ...)` - one mechanism, not two |
| `yotresultsdistribution.report.email.enabled` | `${YOTRESULTSDISTRIBUTION_REPORT_EMAIL_ENABLED:false}` | The e-mail output, switchable independently of the Log Analytics output (FR-006). False until the notificationnotify team provides the template, and named here because the moment it is true the two settings below become required at startup - a deployment that sets the variable and nothing else does not start |
| `yotresultsdistribution.report.email.template-id` | `${CR_REPORT_TEMPLATE_ID:}` | UUID. Required when `email.enabled` is true; startup refuses a blank or malformed value naming the setting, exactly as the P9 rule refuses `cr_standard` |
| `yotresultsdistribution.report.email.recipients` | `${YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS:}` | Comma-separated addresses, from Key Vault through the CSI driver. **Never a chart value and never a default in this repository**: they are people's addresses. Required and non-empty when `email.enabled` is true, and each must parse as an address |

One mechanism per defaulted duration is the rule: a record value resolved once in the validator, or
a placeholder with a literal default, never both. **Which of the two mechanisms each defaulted
duration takes, and why, is written once - in data-model.md's "How the two borrowed and defaulted
durations resolve" table - and is deliberately not restated here or in research.md.** The table
above says what each key is for; the table there says how it resolves.

There is **no `yotresultsdistribution.report.window`**. The scheduled window is
`ReportWindow.sinceLastScheduledRun(cron, zone, now)` - from the previous occurrence of the report's
own cron to now - so Monday reads back to Friday, every FAILED row lands in exactly one report, and
a schedule change carries the window with it. A window expressed as a duration beside a schedule is
the same fact written twice, and the morning the two disagree is the morning something falls in the
gap. The CLI keeps `--since`, and an absent `--since` uses the same computation.

## Constitution Check

*GATE: evaluated against constitution v3.1.0 (2026-09-10). Re-checked after Phase 1 design; no
verdict moved.*

| # | Principle | Verdict for this increment |
|---|---|---|
| I | Defect-Fix-First with Characterised Legacy | **PASS.** There is **no legacy oracle for this capability**: neither the function app nor progression's leg produced an exception report, and the four instruments this increment adds are design section 11 promises that were never built rather than legacy behaviours that were built wrongly. Nothing ported moves, no golden file moves, and no `doc/DEFECT-FIXES.md` row is added or amended - a new capability is not a deviation from the oracle, and inventing a `C` or `P` number for it would make the register say a defect existed where none was catalogued. `RegisteredDefectFixes` and `DifferentialAuditTest` are unaffected and must stay green. |
| II | Test-Driven Development | **PASS.** Every task in the Phase-2 list is a red/green pair: the test task lands its compile-safe seams so the recorded red run is a failing assertion and never a compile error, and the paired implementation task quotes the green run. The test matrix below names a test for every FR and every SC, and the tasks order them before the production code they pin. |
| III | Message-Contract First | **PASS.** **No contract changes at all.** The inbound `distribution-command.schema.json` is untouched; the register document is untouched; the four consumed platform contracts are untouched. Two of them are *used*, exactly as vendored by 002: the notificationnotify `send-email-notification` body (`specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json`, which the e-mail sink's WireMock test validates against) and the file-service `metadata` + `content` schema (`specs/002-consolidate-progression-leg/contracts/fileservice/`, changesets 001-006, which `storeText` writes through unchanged). Nothing is vendored here (`contracts/README.md` says so and points at both). There is no REST surface; the CLI is the operations surface. The `YotResultsDistributionService` flag gains **no** new reader: the report neither reads it nor is gated by it. |
| IV | Canonical JSON In, Typed Models Out | **PASS.** This increment has **nothing inbound**. The report is a typed outbound model throughout: `ExceptionReport` over `ExceptionEntry` records with a bounded `ExceptionKind`, produced from typed repository projections (`ProcessedRequestSummary`, `FailedNotification`, `RegisterBatch`, `RegisterRecord`). No free text another system wrote crosses into the model: `sdg_reason` is in no select list, so a `BATCH_FAILED` entry can only carry its bounded `BatchFailureReason`. The CSV is rendered from those records, and the `send-email-notification` body is the same typed record shape 002 posts. No `JsonNode` crosses this increment's core. |
| V | SOLID with Ports and Adapters | **PASS.** **Two** new ports, each one capability: `ExceptionReportSink` (two adapters, `adapter/report/LogEventReportSink` and `adapter/report/EmailReportSink`) and `ReportMailer` (one adapter, `adapter/notificationnotify/NotificationNotifyReportMailer`), plus one method added to the existing `PayloadFileStore` port and one to `RegisterStore`. The estate's port count goes from twelve to **fourteen**. `ReportMailer` is a second port rather than a widened `RegisterNotifier` because that port's argument is a batch's notification row and its body is `personalisation.yotsName`: bending it around a report would put an "unless it is a report" branch on the one path that e-mails Youth Offending Teams about real registers. The **HTTP shape is shared, not copied** - a package-private request builder in `adapter/notificationnotify/` composes the URI, media type, identity header and 202-only classification for both adapters. `ExceptionReportService` depends on the repositories and the sinks and on nothing else; `ExceptionReportJob` and `IntakeAgeSweep` hold a clock, a lock and collaborators and no driver, broker or HTTP client. **The logging library is confined to one adapter**: `net.logstash.logback.argument.StructuredArguments` appears in `LogEventReportSink` and nowhere else, which is the whole reason the log output is a sink rather than a `log.info` in the service. **Micrometer stays out of `application/` too**: the request-duration timer is started and stopped through `ProcessingMetrics` methods that hand back and take an opaque `ProcessingMetrics.Timing` token, so `DistributionPipeline` holds a token and imports no `Timer.Sample`. Constructor injection, `private final`, no field `@Autowired`. |
| VI | Explicit Failure - Nothing Is Ever Swallowed | **PASS, on the "map to a recorded state" branch of the principle, and on the design rules' one stated exception for the sweep.** `.claude/rules/design_rules.md`, "Error Handling and Logging", says the exception in as many words: *"The one absorbed refusal is telemetry: a round-trip reading that cannot be taken may not cost a Youth Offending Team its e-mail, so it stops where it happens, is counted, and is said at WARN. Every other refusal still leaves."* **The sweep's failed read is that clause exactly** - a reading taken for two gauges and nothing else, so the gauges keep their last value rather than dropping to a lie, `yotresultsdistribution_intake_sweep_failures_total{reason}` moves, one WARN line names the caught failure by class, and the fixed delay is not cancelled. **The report run is not that clause and takes the other branch**: a failed **read** is the report itself, so it counts `outcome=failed`, writes the run line and **rethrows** (releasing the lock); a failed **sink** is classified into a `DeliveryOutcome` with a bounded `ReportDeliveryReason`, counted on `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`, and the run's `outcome` is `partial` on both the `exception_report_run` line and `yotresultsdistribution_exception_report_runs_total{outcome}`, with `ReportExceptionsCli` exiting **2** when any sink it asked failed. **Nothing is retried on either branch**, and that is the point: a report is not a message, it is regenerated in full by the next scheduled run or on demand, so a retry would re-send a list support is about to receive again anyway. A sink is caught **to classify**: every catch produces a `DeliveryOutcome` carrying a bounded `ReportDeliveryReason`, which is counted on `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`, said at WARN, and returned to the run, which reports `PARTIALLY_DELIVERED` rather than success or silence (FR-007). Nothing returns a success value from a catch. The run itself counts its own outcome on `yotresultsdistribution_exception_report_runs_total{outcome}`, so "nothing was wrong" (a summary event with five zeroes, FR-012) and "the report did not run" are different observations. A read that fails part-way through a run is not absorbed: the run counts `outcome=failed`, writes its run line, and rethrows, so the lock is released and the failure is visible rather than logged and dropped. A recipient the send refused is a `FAILED` per-recipient outcome, not a lost one. |
| VII | Privacy in Telemetry | **PASS.** Every event field is an identifier, a bounded code, a count or a timing: no defendant name, address, date of birth, ASN or URN anywhere, and **no recipient address at all** - no read this feature makes selects `register_notification.email_address`, so neither the CLI table nor either event has an address to mask. Masking survives in the one place an address genuinely flows: the e-mail sink's own lines about the recipients it is sending to, masked exactly as `ListBatchesCli` does (`***` where the local part is too short to keep a character of). The CSV attachment carries the same bounded fields as the events, which is why it is safe to attach. `runId` is on every line of the 07:00 run, of the sweep and of the command, through `RunCorrelation.under(...)` opened by each of the three; the service takes the id as an argument (`build(ReportWindow, String runId)`) rather than reading the MDC, which is what makes FR-011 true on the CLI path as well as the scheduled one and keeps `application/` free of the MDC (Principle VII, v3.1.0). Metric labels are `sink`, `outcome` and `kind`, each drawn from a closed enumeration, and never an identifier. No throwable this service did not write is attached to any line; a caught failure is named by class. `TelemetryPrivacyTest` grows a `GenerationLegs` drive over all **seven** new classes - `ExceptionReportJob`, `IntakeAgeSweep`, `ExceptionReportService`, `LogEventReportSink`, `EmailReportSink`, `NotificationNotifyReportMailer` and `ReportExceptionsCli` - and an operator-token group entry for the new command. The mailer is on the list because it is the one class besides the sink that holds an address. Nothing in a `personalisation` map is ever an identifier or an address: the counts and the window travel there (the vendored schema's `personalisation` is `additionalProperties: true`), and the exception list travels as the CSV, by file id. `sdg_reason` reaches nothing, because it is never selected. |
| VIII | Estate Conventions | **PASS.** Gradle, no Maven; PMD and Checkstyle over main and test in `check`; the JaCoCo ratchet unchanged and not loosened; `uk.gov.hmcts.cp.yotresultsdistribution` package root; Conventional Commits with no AI attribution; branch `003-exception-report` merging to `main` the way 002 did. Logging stays SLF4J over Logback with the single `LoggingEventCompositeJsonEncoder` console appender; the only change is one `<arguments/>` provider added to **both** `logback.xml` and `logback-cli.xml`, with `TelemetryPrivacyTest.ShippedConfiguration` extended to require it in both, since a command's lines reach the same index as a pod's. |

## Project Structure

### Documentation (this feature)

```text
specs/003-exception-report/
├── spec.md
├── plan.md              # This file
├── research.md          # Decisions with rationale and alternatives
├── data-model.md        # V4 and V5 indexes, the report model, the projections, the predicates
├── quickstart.md        # Local run, the CLI, reading the events, the KQL support will use
├── contracts/
│   └── README.md        # Nothing vendored here; points at 002's notificationnotify and fileservice
├── checklists/
└── tasks.md             # Phase-ordered TDD task list (/speckit-tasks)
```

### Source Code (repository root) - additions and changes

```text
src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/
├── application/
│   ├── ExceptionReportService.java        # NEW: build(ReportWindow, String runId) reads;
│   │                                      #   deliver(ExceptionReport, Collection<ExceptionReportSink>)
│   │                                      #   answers List<DeliveryOutcome> - the caller chooses the sinks
│   ├── ExceptionReportSink.java           # NEW port: DeliveryOutcome deliver(ExceptionReport)
│   ├── ReportMailer.java                  # NEW port: MailOutcome send(ReportMail) - the report's own
│   │                                      #   send, so RegisterNotifier keeps its register-only shape
│   ├── DistributionPipeline.java          # CHANGED: one settlement is one recording - the count
│   │                                      #   and the duration are one call (review gate 8)
│   ├── PayloadFileStore.java              # CHANGED: + storeText(UUID, String, PayloadMetadata)
│   └── RegisterStore.java                 # CHANGED: + recordedUnbatchedBefore(Instant), the projection
│                                          #   with its age in SQL; activeUnbatched() unchanged
├── domain/
│   ├── ExceptionReport.java               # NEW: runId, window, snapshotAt, entries, truncated,
│   │                                      #   counts - stated rather than derived, so the cap
│   │                                      #   cannot shrink them (review gate 8)
│   ├── ExceptionEntry.java                # NEW: one exception, of one kind
│   ├── ExceptionKind.java                 # NEW enum: REQUEST_FAILED, REQUEST_LATE, BATCH_LATE,
│   │                                      #   BATCH_FAILED, NOTIFICATION_FAILED
│   ├── ReportWindow.java                  # NEW: from (inclusive), to (exclusive),
│   │                                      #   + forScheduledRun(cron, zone, firedAt) - both ends
│   │                                      #   the schedule's - and sinceLastScheduledRun(cron,
│   │                                      #   zone, now) for the caller with no occurrence
│   ├── LastScheduledRun.java              # NEW: the most recent occurrence of a given cron in a given
│   │                                      #   zone before now - the generation cron for the unbatched
│   │                                      #   rule, the report cron for the window. In domain/, not
│   │                                      #   batch/: a pure computation, and ReportWindow calls it
│   ├── DeliveryOutcome.java               # NEW: sink, status, reason, accepted, refused
│   ├── ReportSinkName.java                # NEW enum: LOG, EMAIL (the bounded `sink` label)
│   ├── ReportRunOutcome.java              # NEW enum: DELIVERED, PARTIAL, FAILED, and the fold
│   │                                      #   both run lines and the run counter share
│   ├── DeliveryWord.java                  # NEW enum: OK, FAILED, SKIPPED, DISABLED, and the one
│   │                                      #   `said(...)` the job and the command both call
│   ├── SweepFailureReason.java            # NEW enum: STORE_UNAVAILABLE, UNEXPECTED - the sweep's
│   │                                      #   two bounded reasons, typed after review gate 3
│   ├── DeliveryStatus.java                # NEW enum: DELIVERED, PARTIALLY_DELIVERED, NOT_DELIVERED
│   ├── ReportDeliveryReason.java          # NEW bounded enum (data-model.md)
│   ├── ReportMail.java                    # NEW: notificationId, templateId, sendToAddress, fileId,
│   │                                      #   Map<String,String> personalisation
│   ├── MailOutcome.java                   # NEW: status + responseCode, with MailStatus
│   ├── MailStatus.java                    # NEW enum: ACCEPTED, REFUSED, FAILED, UNANSWERED
│   ├── RequestStatus.java                 # CHANGED: + isTerminal(), which the request timer and
│   │                                      #   RequestOutcome.of(...) both read
│   ├── ProcessedRequestSummary.java       # NEW: the intake projection the report reads
│   ├── FailedNotification.java            # NEW: the notification projection, with its batch's keys
│   ├── BatchException.java                # NEW: the batch projection - batchId, courtCentreId,
│   │                                      #   registerDate, status, failureReason, attempts,
│   │                                      #   ageSeconds; NO sdgReason component at all
│   └── RecordedRegisterSummary.java       # NEW: the recorded-unbatched projection, age in SQL
├── batch/
│   ├── ExceptionReportJob.java            # NEW: @Scheduled(cron, zone, scheduler) +
│   │                                      #   @SchedulerLock("exception-report"), void wrapper ->
│   │                                      #   body returning ExceptionReport; writes the
│   │                                      #   exception_report_run line after every sink returns
│   ├── IntakeAgeSweep.java                # NEW: @Scheduled(fixedDelayString, scheduler), and NO
│   │                                      #   @SchedulerLock - every JVM refreshes its own gauges
│   ├── RunCorrelation.java                # CHANGED: the class and under(...)/current() become public
│   │                                      #   so batch/cli can open a run of its own. Nothing else
│   │                                      #   moves, and the clear-on-exit behaviour is unchanged
│   ├── RegisterGenerationJob.java         # CHANGED: run() gains scheduler = GENERATION_SCHEDULER
│   ├── GenerationReconciler.java          # CHANGED: reconcileScheduled() gains the same attribute
│   ├── cli/ ReportExceptionsCli.java      # NEW: the sixth command
│   ├── cli/ Args.java                     # CHANGED: + SINCE option, + EMAIL flag
│   └── cli/ CliMain.java                  # CHANGED: + REPORT_EXCEPTIONS in COMMANDS and registryOf
├── adapter/report/
│   ├── LogEventReportSink.java            # NEW: StructuredArguments.value(...) - the only logstash import
│   └── EmailReportSink.java               # NEW: CSV -> PayloadFileStore.storeText -> ReportMailer
├── adapter/notificationnotify/
│   ├── NotificationNotifyReportMailer.java # NEW: the ReportMailer adapter, same HTTP shape
│   ├── NotificationNotifyCommand.java     # NEW package-private: the URI, the vendor media type,
│   │                                      #   the CJSCPPUID header and the 202-only rule, shared
│   │                                      #   by both adapters so "the same shape" is a fact
│   └── NotificationNotifyClient.java      # CHANGED: its request composition moves behind that
│                                          #   builder. The RegisterNotifier body and behaviour
│                                          #   are unchanged
├── adapter/fileservice/
│   └── FileServicePayloadStore.java       # CHANGED: storeText, the same two inserts in the same order
├── adapter/stub/
│   └── StubPayloadFileStore.java          # CHANGED: + storeText, the no-op behind the same port
├── persistence/
│   ├── ProcessedRequestRepository.java    # CHANGED: + failedBetween, + nonTerminalOlderThan,
│   │                                      #   + oldestNonTerminal; ages computed in SQL
│   ├── RegisterBatchRepository.java       # CHANGED: + latePending, + lateGenerating, + lateGenerated,
│   │                                      #   + failedBetween, all answering BatchException with the age
│   │                                      #   in SQL; sdg_reason never selected. 002's three entity
│   │                                      #   reads are untouched
│   ├── JdbcRegisterStore.java             # CHANGED: + recordedUnbatchedBefore, sharing
│   │                                      #   activeUnbatched()'s predicate, written once
│   └── RegisterNotificationRepository.java # CHANGED: + failedBetween
└── config/
    ├── ReportProperties.java              # NEW @ConfigurationProperties("yotresultsdistribution.report")
    ├── YotResultsDistributionProperties.java       # CHANGED: + nested Intake(gaugeRefresh=10m)
    ├── CliModeConfig.java                 # CHANGED: the CLI mode key is a constant the scheduling
    │                                       #   configurations and NotCliMode all read, written once
    ├── GenerationProperties.java          # CHANGED: the courts-zone rule is shared with the
    │                                       #   report's, the same rule rather than a similar one
    ├── GenerationHealth.java              # CHANGED: follows the beans that moved to
    │                                       #   FileServiceConfig; nothing it reports changes
    ├── FileServiceRunHealthIndicator.java # CHANGED: the same relocation, and no more
    ├── SchedulingInfrastructureConfig.java # NEW: @EnableScheduling, @EnableSchedulerLock, LockProvider,
    │                                       #   active whenever not CLI - no enabled-flag condition at all,
    │                                       #   because the sweep needs @Scheduled on every service JVM.
    │                                       #   @EnableSchedulerLock keeps its current default,
    │                                       #   RegisterGenerationJob.LOCK_AT_MOST_FOR, verbatim
    ├── SchedulingConfig.java              # CHANGED: keeps the generation scheduler and job beans only,
    │                                       #   and publishes GENERATION_SCHEDULER, the existing bean's
    │                                       #   name, as a public constant. No bean is added or renamed
    ├── ReportSchedulingConfig.java        # NEW: behind yotresultsdistribution.report.enabled - the report's own
    │                                       #   TaskScheduler ("exception-report-"), REPORT_SCHEDULER
    │                                       #   and the job bean
    ├── IntakeSweepConfig.java             # NEW: @Conditional(NotCliMode) + @Profile("!test") ONLY -
    │                                       #   its own single-thread TaskScheduler ("intake-sweep-"),
    │                                       #   INTAKE_SWEEP_SCHEDULER and the IntakeAgeSweep bean
    ├── ProcessedLogConfig.java            # CHANGED: gains registerBatchRepository and
    │                                       #   registerNotificationRepository, relocated out of
    │                                       #   GenerationConfig so the report wires with generation off,
    │                                       #   plus exceptionReportService and logEventReportSink - the
    │                                       #   two the job and the command both ask for, and neither
    │                                       #   context declares the other's scheduling configuration
    ├── GenerationConfig.java              # CHANGED: the two repository beans above move out of it
    ├── ReportEmailConfig.java             # NEW: behind yotresultsdistribution.report.email.enabled alone -
    │                                       #   the ReportMailer over its own RestClient and the
    │                                       #   EmailReportSink, so the second output works on a pod
    │                                       #   that generates nothing (FR-004)
    ├── FileServiceConfig.java             # NEW: the LIVE and STUB PayloadFileStore beans, relocated
    │                                       #   out of LiveGenerationConfig and StubGenerationConfig
    │                                       #   because both outward legs write a file; the mode key
    │                                       #   yotresultsdistribution.generation.fileservice-mode is unchanged
    ├── FileServiceNeeded.java             # NEW Condition: generation.enabled OR report.email.enabled -
    │                                       #   an OR, which @ConditionalOnProperty cannot express, and
    │                                       #   not a cutover lever: the one lever is still the flag
    ├── FileServiceDataSourceConfig.java   # CHANGED: @Conditional(FileServiceNeeded) in place of the
    │                                       #   generation half's switch, so the pool exists wherever
    │                                       #   a file is written
    ├── LiveGenerationConfig.java          # CHANGED: payloadFileStore moves to FileServiceConfig;
    │                                       #   documentRenderer and the request factory unchanged
    ├── StubGenerationConfig.java          # CHANGED: the stub payloadFileStore moves with it; the
    │                                       #   renderer, notifier and flag stand-ins are unchanged
    ├── ProcessingMetrics.java             # CHANGED: two gauges, one timer (behind an opaque Timing
    │                                       #   token), four counters
    └── PropertiesValidator.java           # CHANGED: the report's refusals, reusing SCHEDULER_LOCK_MARGIN,
                                           #   the one null resolution (batch-generated-within), and
                                           #   yotresultsdistribution.fileservice.url required by whichever half
                                           #   writes a file, the refusal naming the half that asked
src/main/resources/db/migration/V4__processed_request_report_indexes.sql
src/main/resources/db/migration/V5__exception_report_batch_and_notification_indexes.sql
                                           # the four batch reads' partial indexes and the
                                           # notification read's (review gate 8)
src/main/resources/application.yaml        # the yotresultsdistribution.report block
src/main/resources/logback.xml             # + <arguments/>
src/main/resources/logback-cli.xml         # + <arguments/>
docker/startup.sh                          # + report-exceptions in the case and in CLI_COMMANDS
docker-compose.yml                         # + the report env vars on the `app` service
scripts/container-smoke.sh                 # + the report-exceptions leg through the entrypoint
```

### Port contracts (this increment)

```java
public interface ExceptionReportSink {

    /**
     * Delivers one report, answering how it went rather than throwing.
     *
     * <p>A sink that could not deliver says so with a bounded reason. It never stops the other
     * sink and it never ends the run: a report that reached one of its two audiences is a
     * partially delivered report, not a failed one (FR-007).
     */
    DeliveryOutcome deliver(ExceptionReport report);
}

public interface PayloadFileStore {                              // 002's port, one method added

    void store(UUID fileId, JsonNode payload, PayloadMetadata metadata)
            throws PayloadStoreUnavailableException;

    /**
     * Stores a text file (the exception CSV) under an id the caller has already minted and
     * written down, through the same two inserts in the same order as {@link #store}: content
     * first, because {@code metadata.file_id} is a foreign key onto {@code content.file_id}.
     */
    void storeText(UUID fileId, String text, PayloadMetadata metadata)
            throws PayloadStoreUnavailableException;
}

public interface ReportMailer {

    /**
     * Sends one report e-mail, answering how it went rather than throwing.
     *
     * <p>A second port rather than a widened {@code RegisterNotifier}: that port's argument is a
     * batch's notification row and its body carries {@code personalisation.yotsName}, so bending it
     * around a report would put an "unless it is a report" branch on the one path that e-mails
     * Youth Offending Teams about real registers. The HTTP shape the two adapters post is shared
     * through a package-private request builder, so "the same shape" is a fact rather than a claim.
     */
    MailOutcome send(ReportMail mail);
}

public interface RegisterStore {                                 // 002's port, one method added

    /**
     * The registers still recorded, active and unbatched that were recorded before a given moment,
     * as a projection carrying its age from the same statement that selects the row.
     *
     * <p>Shares {@link #activeUnbatched()}'s predicate, written once, and adds the cut-off and the
     * SQL age. It is a projection rather than a filter over the entity read because an age derived
     * in the JVM from a stored timestamp is the cross-clock comparison V1 forbids.
     */
    List<RecordedRegisterSummary> recordedUnbatchedBefore(Instant recordedBefore);
}
```

The **eight** new repository reads - three on `ProcessedRequestRepository`, four on
`RegisterBatchRepository`, one on `RegisterNotificationRepository` - and the sweep's own read are
not ports: `ExceptionReportService` and `IntakeAgeSweep` reach the processed log the way
`IdempotencyGuard` and `ProcessingStateService` already do, through the repositories in
`persistence/`, which is where the JDBC types stay. The ninth read, `recordedUnbatchedBefore`, is a
port method because `RegisterStore` is already a port and the registers are already reached through
it; adding a second route to the same table would be the drift, not the economy. Two of
those repositories - `RegisterBatchRepository` and `RegisterNotificationRepository` - are declared
today inside `GenerationConfig`, which is conditional on `yotresultsdistribution.generation.enabled`, so the
report could not be wired on a generation-disabled pod at all. Their bean declarations move to
`ProcessedLogConfig`, which is generation-neutral and already declares `ProcessedRequestRepository`,
`ProcessedOutputRepository`, `registerStore` and the guard over the same `JdbcClient` and
`PlatformTransactionManager`. Nothing about the repositories themselves changes.

### Test matrix

Layers: **U** unit - **W** WireMock - **PG** Postgres `*IT` - **FS** file-service Postgres `*IT` -
**E2E** full context - **CS** container smoke. **Task authors use these names verbatim.**

| Area | Planned test | Layer | Covers |
|---|---|---|---|
| Schema | `SchemaMigrationV4IT` | PG | V4: both indexes exist, the partial one carries its predicate, V1-V3 unchanged, migration is additive |
| Reads | `ProcessedRequestReportReadsIT` | PG | FR-001: `failedSince` returns FAILED inside the window and nothing else, and **includes a row parked exactly at the window's start** (`failed_since_includes_a_row_failed_exactly_at_the_window_start`, review gate 2 - consecutive windows abut); `nonTerminalOlderThan` returns RECEIVED and RETRYING older than the cut-off, oldest first, and excludes terminal rows; `age_seconds_is_answered_in_seconds_from_the_stage_timestamp` - the value against the seeded age, and the statement the driver was really prepared with against `now()` and `extract(epoch`, which is where the reading is taken (review gate 2 renamed this and the three like it: the old form advanced an `AdjustableClock` no repository holds and then asserted the database's answer had not moved, which it could not have); `oldestNonTerminal` on an empty table answers empty; `countNonTerminalOlderThan` answers the same predicate as a number, zero on an empty table, and `a_request_exactly_at_the_threshold_is_not_over_it` pins the exclusive boundary against the row's **own stored instant read back** (review gate 3 - a cut-off derived from the suite's clock is *near* the boundary, and near is what the case rules out). SC-006's index claim (`every_scheduled_read_is_served_by_a_v4_index`, renamed at review gate 3 when the count read joined it): `EXPLAIN` over every scheduled read names the V4 indexes, run **after `ANALYZE`** (a table the planner has no statistics for is a table it will sequentially scan whatever indexes exist) and **with `SET enable_seqscan = off`** for the session, so the assertion is "this query *can* use the index" rather than "today's row count happened to make it cheapest" - the second is a test that goes green on an empty table and red on a full one |
| Reads | `RegisterNotificationReportReadsIT` | PG | FR-001: `failedSince` returns FAILED rows with their batch's court centre and register date, ordered oldest first, in one statement and not one per row; it **includes a row settled exactly at the window's start**; `age_seconds_is_answered_in_seconds_from_the_stage_timestamp`; `email_address` is in no select list |
| Reads | `RegisterBatchReportReadsIT` | PG | FR-001: the **four** new reads - `latePending`, `lateGenerating`, `lateGenerated` and `failedSince` - each return the right rows oldest first, each answer a `BatchException` carrying `age_seconds` **computed in SQL** from its own stage timestamp (`age_seconds_is_answered_in_seconds_from_the_stage_timestamp`: each read's value against its seeded age, and each read's own recorded statement against `now()` and `extract(epoch`), and `sdg_reason` is in no select list of any of them. Review gate 2 adds `every_read_goes_through_store_outage_translating` - all four were the only statements in the class not wrapped, so an unreachable store reached the report as a `org.springframework.dao` type nobody classified. 002's `pendingSince` / `generatingSince` / `generatedSince` are untouched and their existing cases stay green |
| Reads | `RegisterStoreReportReadsIT` | PG | FR-001: `recordedUnbatchedBefore` answers `RecordedRegisterSummary` with `age_seconds` computed in SQL from `register_time` (`age_seconds_is_answered_in_seconds_from_the_stage_timestamp`); it returns exactly the rows `activeUnbatched()` returns that are older than the cut-off, and a register recorded while the flag was off is absent from both; `activeUnbatched()`'s own cases stay green. Review gate 2 adds `registers_recorded_at_the_same_moment_are_answered_in_output_id_order` - the read ordered on `register_time` alone while `activeUnbatched()` has always broken the tie on `output_id`, so two runs of one report listed the same morning two ways |
| Write-path invariant | `RegisterStoreIT` (extended), `RegisterNotifierServiceTest` (extended) | PG / U | review gate 2: the `BATCH_FAILED` and `NOTIFICATION_FAILED` reads bound on `failed_at` and `sent_at` and measure their ages from them, so a null in either would drop the row out of the predicate in silence. `a_failed_batch_always_carries_its_failed_at` and `a_failed_notification_always_carries_its_sent_at` pin the invariant **where it is produced** - `MARK_FAILED` stamps in the same `UPDATE` as the status, `settledAs` stamps every terminal attempt - which is why the reads carry no `COALESCE` |
| Report | `ExceptionReportServiceTest` | U | FR-001/002/013/014: one entry per exception; the five kinds' fields, including `BATCH_FAILED` carrying its bounded reason and never `sdg_reason`; `each_late_batch_stage_is_asked_about_its_own_limit` - the two rendering stages are held to the rendering limit and a rendered batch to the notification limit, captured off the three reads, because one duration passed to all three would make the three stages one question; the run id is the argument the caller passed; nothing written back (every repository is verified read-only); an empty window yields a report with five zero counts and no entries (FR-012); the same exception appears in two consecutive windows (scenario 6). Review gate 4 adds: `a_request_is_reported_under_at_most_one_kind_per_run` rewritten to seed the **same** `(source, requestId)` in both intake answers and assert one entry, the failure (the old case seeded one read and could not fail); `entries_at_the_same_age_are_ordered_by_kind_then_identifier`; and `a_read_failure_leaves_the_service_and_makes_no_report`, the read-vs-sink half of FR-007 |
| Report | `ExceptionReportDeliveryTest` | U | FR-007: `deliver(report, sinks)` takes the sinks from the caller; a sink that fails is recorded with a bounded reason, counted, and the other sink still runs; the run is `PARTIALLY_DELIVERED`; both sinks failing is `NOT_DELIVERED` and still not an exception out of the service; delivering to the log sink alone (the command without `--email`) is a one-outcome list. Review gate 4 adds `a_sink_whose_name_cannot_be_read_is_still_recorded_and_the_others_still_run` - a sink names itself off the thing it delivers through, so the one that has just broken is the one that may no longer answer, and asking it inside the catch made the one delivery nobody planned for the one that escaped |
| Batch late | `LastScheduledRunTest` (in `domain/`) | U | the most recent occurrence of a given cron in a given zone before a given instant, across a BST/GMT boundary and over a weekend: the 18:00 generation cron for the never-batched rule, and the 07:00 report cron for the two `ReportWindow` factories; a register recorded after it is not late. Review gate 2 adds `an_occurrence_at_the_instant_itself_is_answered_by_at_or_before_and_not_by_before` - the boundary that separates the two factories, and the whole difference between `before` and the new `atOrBefore` |
| Log sink | `LogEventReportSinkTest` | U | FR-005/SC-003: `an_empty_report_writes_exactly_one_event` - the summary alone, a nought per kind, no exception event for an exception nobody had; one `yotresultsdistribution_exception_report` event with the **twelve** summary fields since increment 004 (`event`, `run_id`, three instants, one count per kind, `truncated`) and **no delivery status of any kind** - a sink cannot observe its own delivery, let alone the other sink's; one `yotresultsdistribution_exception` per entry with only the fields applicable to its kind, out of the thirteen components the entry carries including `kind`; every value is an identifier, a bounded code or a number; `runId` present on both |
| Report model | `ExceptionReportModelTest` | U | the domain records that carry behaviour, in three nested classes. **The window**: `ReportWindow` refuses a backwards window and one of no width (`a_window_with_no_width_is_refused`); `forScheduledRun(cron, zone, firedAt)` opens at the run before the fire's own occurrence whether it fired exactly on it (`a_run_firing_exactly_on_the_occurrence_still_starts_at_the_previous_run`), fifty milliseconds late, or half an hour late (`a_late_start_at_half_past_still_starts_at_the_previous_run` - the window widens and never shrinks), and a Monday run reaches back to Friday; `sinceLastScheduledRun(cron, zone, now)` takes **one** step, so a bare call at 06:59 on a Tuesday opens at Monday's run and one at 09:00 opens at that Tuesday's (review gate 2 split the one factory into two: the bare call was reaching a whole period too far back, and the run was right only because it fires late). **The report**: `counts()` answers zero for every kind that has none, asserted over all five rather than by `containsValue(0)` (FR-012); an entry with no kind and a report with a null entries list are refused, and review gate 4 adds `a_batch_failed_entry_with_no_bounded_reason_is_refused` - a dead batch carrying nothing says only that a batch ended. **The mail**: `ReportMail`'s compact constructor treats an absent personalisation map as none and copies the one it is given. Plus a delivered `LOG` outcome is one accepted and none refused |
| E-mail sink | `EmailReportSinkTest` | U | FR-006: the CSV is stored **before** any send and its `fileId` is the one on every `ReportMail`; one `ReportMailer.send` per recipient; the personalisation map carries the five counts and the window as strings and no identifier and no address; one refused recipient does not stop the other two and folds to `PARTIALLY_DELIVERED` with accepted 2 and refused 1 (scenario 4.2); an empty report is still sent (scenario 4.4); an empty resolved recipient list at run time is `NO_RECIPIENTS`; every address is masked in every line the sink writes. It holds the port and no HTTP type, which is why this is a `U` and not a `W` |
| Mailer | `NotificationNotifyReportMailerTest` | W | FR-006 and Principle III: the body posted for a report e-mail is exactly `templateId`, `sendToAddress`, `fileId`, `personalisation`, validated against the **002-vendored** `notificationnotify.email.json` (`additionalProperties: false` at the top level, `personalisation` `additionalProperties: true`, so arbitrary personalisation keys are contract-legal and the counts may travel in the body); the personalisation values are strings; media type `application/vnd.notificationnotify.email+json`; `CJSCPPUID`; `notificationId` is the **path** parameter and not a body field; 202 and nothing else is success; a 4xx is `REFUSED` and never retried; 408/429/5xx are transient. `NotificationNotifyClientTest`'s existing register-path cases are untouched and stay green over the shared request builder |
| File store | `FileServicePayloadStoreIT` (extended) | FS | `storeText` issues exactly two statements, content before metadata, content `bytea` holding UTF-8, and writes the five metadata keys - the port method's own pin, in the suite that owns `FileServicePayloadStore` |
| E-mail sink | `EmailReportSinkStoreIT` | FS | the sink's **composition**: the CSV's header and thirteen columns, one row per entry in the entries' own order, UTF-8 with `\n` endings and RFC 4180 quoting, no address and no defendant data in any cell, and the metadata the sink hands down (`conversionFormat=csv`, `templateName=yot-results-distribution-exception-report`, `numberOfPages=1`, `fileSize` the byte count, `fileName` `court-register-exceptions_{yyyy-MM-dd}.csv` dated in `Europe/London`) |
| Job | `ExceptionReportJobTest` | U | FR-003/004/011: the scheduled method is `void` and carries `@Scheduled(cron, zone, scheduler = ReportSchedulingConfig.REPORT_SCHEDULER)` and `@SchedulerLock(name = "exception-report", lockAtMostFor = "${yotresultsdistribution.report.lock-at-most-for}")`, asserted by reflection (`the_report_is_scheduled_in_europe_london`); the window the job asks for is the one `ReportWindow.forScheduledRun` answers - **asserted over a mock**, because the computation itself is `LastScheduledRunTest`'s and `ExceptionReportModelTest`'s and a third copy of the Monday-to-Friday arithmetic is a third place to change it; `runId` is opened for the run, passed into `build(...)` and removed afterwards; **the `exception_report_run` line is written after every sink has returned**, carrying `run_id`, the window, `entries`, `delivered_log`, `delivered_email` (`ok`/`failed`/`disabled` - `skipped` is the command's fifth value and never the job's), `outcome` and `duration_ms`; the run counts its outcome; a read failure counts the run failed, writes the run line and rethrows so the lock releases; the flag is never read. Review gate 5 adds `the_body_takes_its_run_id_from_the_caller_and_never_reads_the_mdc` - the body is documented as directly callable, so it takes the id as an argument and `run()` is what opens and removes the correlation - `a_run_asked_of_one_sink_that_failed_is_outcome_failed` over the MVP's own one-sink deployment, and `the_failure_paths_run_line_carries_every_field`, because the morning that produced nothing is the morning whose line is read hardest |
| Scheduling | `RegisterGenerationJobTest.WhenItRuns` (extend), `GenerationReconcilerTest.ItsOwnSchedule` (extend), `ExceptionReportJobTest`, `IntakeAgeSweepTest` | U | SC-008: **all four** scheduled methods carry `@Scheduled(scheduler = ...)` naming the constant their own configuration publishes - `SchedulingConfig.GENERATION_SCHEDULER` for the two generation surfaces (the existing `registerGenerationScheduler` bean, no new bean), `ReportSchedulingConfig.REPORT_SCHEDULER`, `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER` - read by reflection in the suite that owns each class, the way the two generation cases already read `cron`, `zone` and `@SchedulerLock`. Without the attribute, three `TaskScheduler` beans route nothing and SC-008's separation is a comment |
| Sweep | `IntakeAgeSweepTest` | U | FR-008: both gauges move from the repository's answers; both return to zero when nothing is unfinished; the scheduled method carries `@Scheduled` and **no** `@SchedulerLock`, so every replica refreshes its own gauges; it opens its own `runId`; the fixture is spec scenario 2.2 verbatim - forty minutes old against a thirty-minute threshold (review gate 3: the old one answered a 900 s row as oldest while a 2400 s row sat in the same store, and then counted the 900 s one as over 1800 s); the over-threshold gauge is set from `countNonTerminalOlderThan` and **not** from a materialised list, and `the_cut_off_is_the_threshold_ago_exactly_and_is_not_nudged_either_way` captures the instant it passes; **two** absorbed-refusal cases, `store-unavailable` and `unexpected`, each leaving the gauges at their last reading, counting `yotresultsdistribution_intake_sweep_failures_total{reason}`, writing one WARN line that names the caught failure by class and repeats **neither its own message nor its cause's**, and not cancelling the schedule - the design rules' "one absorbed refusal is telemetry" clause, and the counter is what makes the absorption visible |
| Metrics | `ProcessingMetricsTest` (extended) | U | FR-008: `yotresultsdistribution_oldest_non_terminal_request_age` and `yotresultsdistribution_non_terminal_requests_over_threshold` exist **from construction** and read zero (scenario 2.1); `yotresultsdistribution_request_duration{outcome}` records one sample per terminal transition through the opaque `Timing` token, tagged only by the terminal outcome; the **four** counters and their bounded labels - `yotresultsdistribution_exception_report_runs_total{outcome}` over `delivered`/`partial`/`failed`, `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`, `yotresultsdistribution_exceptions_reported_total{kind}` over all five kinds, and `yotresultsdistribution_intake_sweep_failures_total{reason}` - the run counter and the sweep counter bounded **by type** after review gate 3 (`the_run_outcome_label_is_one_of_three_bounded_words`, `the_sweep_failure_reason_label_is_one_of_two_bounded_codes`: the rendered labels, and that no String-taking overload survives beside the enum one); `a_non_terminal_status_is_refused_by_the_timer` - `requestSettled(Timing, RETRYING)` throws `IllegalArgumentException` rather than publishing a fifth `outcome` series, over `RequestStatus.isTerminal()`, which `domain/RequestStatusTest` pins |
| Pipeline | `DistributionPipelineTest` (extended) | U | the duration timer is recorded once per terminal transition, in `settled(...)` and `parked(...)`, and not at all where the guard refused the write - on **either** side of that refusal, the completion one and the parking one (`a_parked_run_that_was_not_dead_lettered_records_no_sample`, review gate 3: the parking side had no case, although the two are separate `instanceof` branches and one could be widened without the other); the pipeline holds a `ProcessingMetrics.Timing` token and imports no Micrometer type, asserted over a source path resolved from `user.dir` |
| Config | `ReportPropertiesTest` / `ConfigurationValidationTest` (extended) | U | FR-010: the defaults, including `yotresultsdistribution.intake.gauge-refresh` at `10m`; a zero or negative threshold, refresh or lock refused naming the setting, `batch-generated-within` included where it is set explicitly; a zone other than `Europe/London` refused without the acknowledgement; `email.enabled` with no template or no recipient refused naming the setting (scenario 4.3); an unparseable recipient refused; `lock-at-most-for` below `REPORT_RUN_BUDGET` plus the existing `SCHEDULER_LOCK_MARGIN` refused; an unset `batch-generated-within` resolving to the generation grace period, and an explicitly set one honoured over the grace period beside it (`an_explicit_batch_generated_within_is_honoured`). There is no window setting to refuse. Two **should-start** counterparts sit beside the refusals, so a rule that refuses everything is as visible as one that refuses nothing: `an_acknowledged_override_of_a_known_zone_should_start` and `an_enabled_email_output_with_everything_it_needs_should_start` (three settings and not two since review gate 7: the file-service URL is one of them). Review gate 1 adds: `an_unparseable_cron_refuses_to_start` (the cron is the trigger **and** the window, so an unreadable one is a job that never fires and a window nothing can open), `a_zero_grace_period_makes_the_unset_rendering_limit_refuse` (the resolved limit is held to being positive too, and the refusal names `yotresultsdistribution.generation.grace-period` - the key the value really came from), `a_malformed_template_id_refuses_to_start`, `a_blank_template_id_with_email_enabled_refuses_to_start`, `a_recipient_list_with_an_empty_entry_refuses_to_start` (both spellings of a stray separator), and `an_acknowledged_override_must_still_be_a_zone_the_jvm_knows` sharpened onto the words only that branch says, so it cannot pass on the unacknowledged refusal. No refusal quotes a cron, a template id or an address back; a duration and a zone id are echoed, as the class's existing refusals echo them. **SC-005 / user story 5 scenario 3** is `ReportPropertiesTest`'s, not this row's: two `ApplicationContextRunner`s differing only in `request-terminal-within` yield two contexts whose resolved threshold differs and whose other settings do not - a threshold is per environment and takes effect on the next run with no release. Review gate 8 adds `a_zero_max_entries_refuses_to_start` and `the_system_user_id_is_required_whenever_either_half_sends` |
| Wiring | `ReportSchedulingConfigTest` | U | the infrastructure-presence cases, all in this one class: `SchedulingInfrastructureConfig` is active whenever the JVM is not a command JVM, whatever the two enabled flags say; `IntakeSweepConfig` likewise, on its own single-thread `TaskScheduler` named `intake-sweep-`; `ReportSchedulingConfig` only behind `yotresultsdistribution.report.enabled`, on its own single-thread `TaskScheduler` named `exception-report-`; exactly one `LockProvider`; `the_report_wires_with_generation_disabled` - `report.enabled=true` with `generation.enabled=false` yields a fully wired report, which is what the relocated repository beans buy; the generation beans are unchanged |
| Wiring | `ReportEmailConfigTest` | U | the e-mail output's own beans, which review gate 7 found had no test of their own: a pod with the output on holds the `ReportMailer` and the `EmailReportSink` as singletons; a pod in FR-004's shape - generation off, the output on - **starts**, which it could not while the `PayloadFileStore` beans sat behind the generation switch; a generating pod's store is unchanged; and `yotresultsdistribution.fileservice.url` is required wherever either half needs it |
| Wiring | `StubGenerationAdaptersTest` (extended) | U | follows the `payloadFileStore` bean out of `StubGenerationConfig` into `FileServiceConfig`: the stub is still the bean a STUB deployment holds, contributed by the configuration that owns it |
| Wiring | `CliModeConfigTest` (extended) | U | a CLI context runs **no** scheduled task of any half: none of `SchedulingInfrastructureConfig`, `ReportSchedulingConfig` or `IntakeSweepConfig` is contributed when `yotresultsdistribution.cli=true`, so `@EnableScheduling` is absent and `@Scheduled` is never processed - which is what makes three configurations unconditional on the two enabled flags safe |
| CLI | `ReportExceptionsCliTest` | U | FR-009/FR-011: `--since 2h`, `--since 30m`, `--since PT2H` and `--since <instant>` all resolve the window; an absent `--since` uses the same since-the-previous-scheduled-run window the 07:00 job uses; a malformed value is refused as `unreadable-argument` naming `--since` and never quoting the token; the command opens its own `RunCorrelation` and passes the run id into `build(...)`, so every line and event it produces carries one; it delivers to the log sink always and adds the e-mail sink only under `--email`; one line per exception, oldest first, then the counts line, then **the equivalent of the job's `exception_report_run` line, written after every sink has returned**; no line carries a recipient address, because no read selects one; `--email` refused with `declined` (exit 1) when `report.email.enabled` is false, writing nothing; **an option the command does not accept is refused with the usage line and the refusal exit code (user story 3 scenario 4), like every other operations command**; and the command **exits 2** when any sink it asked failed |
| CLI | `ArgsTest` (extended) | U | `SINCE` is an option and `EMAIL` a flag; `permits` rejects them on the other five commands |
| CLI | `CliMainTest` (extended) | U | `report-exceptions` is in `COMMANDS` and in `registryOf`; the usage line lists six names |
| CLI | `CliDispatchIT` (extended) | CS | `startup.sh report-exceptions --help` exits 0 inside the image; a mistyped name lists six |
| Privacy | `TelemetryPrivacyTest` (extended) | U | SC-007: the `GenerationLegs` drive covers all **seven** - `ExceptionReportJob`, `IntakeAgeSweep`, `ExceptionReportService`, both sinks, `NotificationNotifyReportMailer` and `ReportExceptionsCli` - so every LOG statement in them is reached; no marker and no `sdg_reason` reaches a line, a label or the CSV; the operator-token group covers `report-exceptions`; `ShippedConfiguration` requires `<arguments/>` in **both** logback files. Review gate 4 tightens two of these: the provider claim **parses the XML** and asks the encoder's own provider list, so a commented-out tag (still the characters a text search looks for) and a tag outside the encoder both fail it; and the report precondition inside `should_have_reached_every_line_the_two_legs_can_write` is asked **per class** over `GenerationLegs.THE_REPORT`, so one class writing two lines can no longer cover for another declaring none |
| Privacy | `LogStatementSweepTest` (unchanged, must stay green) | U | no throwable this service did not write is attached anywhere in the seven new classes |
| E2E | `ExceptionReportEndToEndIT` | E2E | SC-001/003/004/006/008: seed a FAILED request, a 40-minute-old RETRYING request, a batch GENERATING past the limit, a FAILED batch and a FAILED notification; run the job with `yotresultsdistribution.generation.enabled=false`; read five `yotresultsdistribution_exception` events and one summary with the five counts and its eleven fields, plus the `exception_report_run` line; with the e-mail output on and WireMock standing in, read one send per recipient and the CSV row for each exception. **SC-001**: a second case seeds **at least fifty mixed records** across the five kinds and the terminal states that are not exceptions, and asserts every FAILED request appears exactly once under `REQUEST_FAILED` - zero misses, zero duplicates - and, since review gate 8, that the number of events equals the number of failures, so a row this case never seeded cannot hide inside an assertion about the identifiers it knows. **SC-006**: a third seeds a **10,000-row** processed log and asserts a `--since 24h` report is built and written in **under ten seconds**. **SC-008**: a fourth runs the report job **concurrently with a generation run** and asserts the two land on differently named threads and that the generation run line's `duration_ms` is within its normal bound. The last two carry `@Tag("timing")` - they stay in the default selection, and `-PexcludeTags=timing` leaves them out by name on a loaded machine |
| Differential | `DifferentialAuditTest` (unchanged, must stay green) | U | no recorded document and no golden moves |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| The scheduling infrastructure is split out of `SchedulingConfig` into `SchedulingInfrastructureConfig`, conditional on **nothing but not being a command JVM**, with two small configurations on top of it: `IntakeSweepConfig` (likewise unconditional on the two enabled flags, its own `intake-sweep-` scheduler) and `ReportSchedulingConfig` (behind `yotresultsdistribution.report.enabled`, its own `exception-report-` scheduler) | The sweep **must refresh the gauges wherever the intake half runs** and the report **must run where the generation half is disabled** (FR-004, FR-008, user story 2 scenario 4). Today `@EnableScheduling`, `@EnableSchedulerLock` and the `LockProvider` all sit inside a configuration conditional on `yotresultsdistribution.generation.enabled`, so an intake-only pod processes no `@Scheduled` at all. A condition of generation-or-report was considered and rejected in this pass: the gauges would still be dark on a pod with both halves switched off, which is exactly the deployment user story 2 scenario 4 names. **Review gate 7 found the same shape again, in the file service**: the `PayloadFileStore` beans and the second datasource sat behind `yotresultsdistribution.generation.enabled`, and the morning report's e-mail output writes a file too - so a pod in FR-004's shape with that output on held neither, and would not have started. They followed the same relocation, into `config/FileServiceConfig` behind a `config/FileServiceNeeded` condition that is generation-**or**-e-mail; here an OR is right precisely because both of its halves write a file, which is the question being asked | Making the report depend on `generation.enabled` was rejected: it would tie a read-only morning report to the switch that decides whether this pod generates documents, which is the coupling that makes a threshold change into a deployment decision. Duplicating `@EnableScheduling` and a second `LockProvider` in a second configuration was rejected: Spring permits one of each per context, and two lock providers over one `shedlock` table is a race dressed as a configuration. The cost of an unconditional infrastructure is an idle scheduler on a pod that schedules nothing; `CliModeConfigTest` proves it is never even that on a command JVM |
| Two bean declarations move between configurations: `registerBatchRepository` and `registerNotificationRepository` leave `GenerationConfig` for the generation-neutral `ProcessedLogConfig` | Both are declared today inside a configuration conditional on `yotresultsdistribution.generation.enabled`, and the report **reads both** - `BATCH_LATE` and `BATCH_FAILED` from one, `NOTIFICATION_FAILED` from the other. Without the move, `yotresultsdistribution.report.enabled=true` with `yotresultsdistribution.generation.enabled=false` - the exact FR-004 deployment, and the MVP's own shape - cannot start, for a missing bean. `ReportSchedulingConfigTest.the_report_wires_with_generation_disabled` is the assertion that keeps it fixed | A second copy of each repository declared in the report's own configuration was rejected: two beans of one repository over one table is the same race as two lock providers, and the pair would drift. A narrower read behind a new port was rejected: the repositories already answer the questions, and a port per read is a layer invented to avoid moving a `@Bean` line. `ProcessedLogConfig` is where they belong in any case - it already declares `ProcessedRequestRepository`, `ProcessedOutputRepository` and `registerStore` over the same `JdbcClient` and transaction manager |
| A second direct write into the framework file service, a database this service does not own (the CSV attachment, `PayloadFileStore.storeText`) | The e-mail's attachment travels **by file id**, the way the register PDF does: notificationnotify's `send-email-notification` takes a `fileId` and reads the file service, so a file that is not there is an e-mail with nothing attached. The design owner closed design Q20 on 2026-09-14 - the file service is the one store outside this service's own that may be written directly - and the consumed-contracts table in `.claude/rules/design_rules.md` records it | A body-only e-mail was rejected: Notify's body has a length limit that a bad morning's list would exceed, it renders no table, and truncating a list of exceptions is the silence this service exists to end. Uploading through systemdocgenerator's `document-payload-upload` was rejected for the reason research section 1 of 002 gives - it hides the id it minted, so the caller cannot correlate |
| A logging-library type (`net.logstash.logback.argument.StructuredArguments`) inside one adapter | Log Analytics must read `kind`, `request_id` and `batch_id` as **separate fields** (SC-003, FR-005), which means the values have to reach the encoder as structured arguments rather than inside the message text | MDC was rejected: it is thread-local and per-line, so a report writing one event per exception on one thread would have to set and clear a dozen keys around every line, and a key left behind leaks onto the next line of the same run - a false correlation is worse than none (the same argument `RunCorrelation` makes about the pooled scheduler threads). A flat `key=value` message, like the run report's line, was rejected: every query would need `parse()`, which is precisely the field parsing SC-003 forbids. The containment is the mitigation: the type appears in `LogEventReportSink` and nowhere else, so `application/` still imports no logging library (Principle V) |

No `doc/DEFECT-FIXES.md` row is added: this is a new capability, not a deviation from a legacy
oracle, and there is no catalogued defect it fixes.
