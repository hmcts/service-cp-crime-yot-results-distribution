# Research: Exception report for production support

Decisions the plan rests on, each with rationale and the alternatives weighed. Settled with the
design owner on 2026-09-14. Source references are to this repository as at branch
`003-exception-report` (`94bd245`, which is `main` at the point this branch left it and the commit
`doc/DEFECT-FIXES.md` was last changed at) unless stated otherwise; the 002 artefacts referred to are in
`specs/002-consolidate-progression-leg/`. Where something was genuinely undecided it is written
below as an assumption rather than left as a marker.

## 1. Architecture - a service, two ports, three adapters

- **Decision**: `application/ExceptionReportService` has **two** methods.
  `ExceptionReport build(ReportWindow window, String runId)` reads the repositories and composes the
  typed report; `List<DeliveryOutcome> deliver(ExceptionReport report,
  Collection<ExceptionReportSink> sinks)` hands it to the sinks **the caller chose**. The port is one
  method, `DeliveryOutcome deliver(ExceptionReport report)`. Two adapters implement it:
  `adapter/report/LogEventReportSink` and `adapter/report/EmailReportSink`. The domain gains
  `ExceptionReport`, `ExceptionEntry`, `ExceptionKind` (`REQUEST_FAILED`, `REQUEST_LATE`,
  `BATCH_LATE`, `BATCH_FAILED`, `NOTIFICATION_FAILED`), `ReportWindow`, `LastScheduledRun`,
  `DeliveryOutcome` and a bounded `ReportDeliveryReason`. `batch/ExceptionReportJob` fires the run
  and `batch/IntakeAgeSweep` refreshes the two intake gauges. The **second** port is `ReportMailer`
  (section 4), which is what keeps the HTTP client out of `EmailReportSink` in the same way
  `ExceptionReportSink` keeps the logging library out of the service.
- **Why the split, and why the run id is an argument.** The 07:00 job delivers to **every** sink on
  the context; the command delivers to the log sink always and adds the e-mail sink **only** under
  `--email`. One method that always fanned out to every sink could not express the second without
  the service learning what a command-line flag is. And the run id is passed in rather than read:
  whoever opened the correlation - the job, or the command - knows it, so `build(window, runId)`
  takes it and `application/` imports no MDC. That is also what makes FR-011 true on the CLI path,
  where nothing would otherwise have opened a run at all.
- **Five kinds, not four.** `BATCH_FAILED` - a `register_batch` row that reached `FAILED` with its
  `failed_at` inside the window - is the downstream half's equivalent of a `FAILED` request. A report
  that listed the late batches but not the dead ones would name the symptom and hide the outcome, and
  the state machine 002 built already records the reason in a bounded enum. It carries `batch_id`,
  `court_centre_id`, `register_date` and `failure_reason` (a `BatchFailureReason`), and it carries
  **`sdg_reason` not at all**: that column is systemdocgenerator's own words about somebody's
  document, free text written by another system, which constitution Principle VII keeps out of a
  line, a label, an event and the CSV alike.
- **Rationale**: the two outputs answer the same question to two audiences and must not be able to
  take each other down (FR-007, and the first edge case). Behind one port they are two
  implementations of one capability, so the service delivers to a list and neither adapter knows the
  other exists. It is also what keeps the logging library out of `application/`: the structured-event
  shape is an *adapter's* concern in exactly the way an HTTP body is (constitution Principle V), so
  `StructuredArguments` lives in `LogEventReportSink` and nowhere else.
- **A sink failure is caught only to classify.** Each adapter answers with a `DeliveryOutcome`
  carrying a `ReportDeliveryReason` from a closed set; the service counts it on
  `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`, says it at WARN naming the caught
  failure by **class** and never by message, and carries on to the next sink. Nothing returns a
  success value from a catch and nothing is logged and dropped (Principle VI).
- **Two different failures, two different disciplines, and both are the repository's own rules
  rather than a preference.** `.claude/rules/design_rules.md`, "Error Handling and Logging", states
  the exception to "nothing is ever swallowed" in as many words: *"The one absorbed refusal is
  telemetry: a round-trip reading that cannot be taken may not cost a Youth Offending Team its
  e-mail, so it stops where it happens, is counted, and is said at WARN. Every other refusal still
  leaves."*
  - **The sweep's failed read is exactly that clause.** It is a reading, taken for two gauges and
    for nothing else. It stops where it happens, the gauges keep their last value rather than
    dropping to a lie, `yotresultsdistribution_intake_sweep_failures_total{reason}` moves, one WARN line is
    written naming the caught failure by class, and the fixed delay is not cancelled. Nothing
    downstream depends on the reading, so absorbing it costs an interval of staleness and buys a
    schedule that survives a database blip.
  - **The report run is not that clause, and takes the other branch.** A failed **read** is not
    telemetry - it is the report - so it counts
    `yotresultsdistribution_exception_report_runs_total{outcome=failed}`, writes the run line, and
    **rethrows**, which releases the ShedLock lock and makes the failure visible. A failed **sink**
    is Principle VI's *"map to a recorded state"* branch: it is classified into a `DeliveryOutcome`
    with a bounded `ReportDeliveryReason`, counted on
    `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`, and the run's `outcome` is
    `partial` on both the `exception_report_run` line and
    `yotresultsdistribution_exception_report_runs_total{outcome}`. `ReportExceptionsCli` exits **2**
    ("could not") when any sink it asked failed, so an operator's shell knows without reading the
    line.
  - **Nothing is retried, on either branch.** A report is not a message: it is regenerated in full
    by the next scheduled run or on demand by the command, so a retry would re-send a list support
    is about to receive again anyway. That is why a sink failure maps to a recorded state rather
    than to an abandon-and-redeliver, and it is the whole reason this increment settles nothing.
- **Alternatives**: one service writing both outputs directly, rejected - it would put a logging
  library and an HTTP client in the same class as the query logic and make "the e-mail failed" and
  "the run failed" the same thing. A Spring `ApplicationEvent` fan-out, rejected - the run has to
  *know* whether each output was delivered, which an event publication cannot tell it.

## 2. The scheduled run and the lock

- **Decision**: `ExceptionReportJob` carries
  `@Scheduled(cron = "${yotresultsdistribution.report.cron}", zone = "${yotresultsdistribution.report.zone}",
  scheduler = ReportSchedulingConfig.REPORT_SCHEDULER)` and
  `@SchedulerLock(name = "exception-report", lockAtMostFor = "${yotresultsdistribution.report.lock-at-most-for}")`
  on a **`void`** method that opens `RunCorrelation.under(...)` and delegates to a body returning
  the `ExceptionReport`. `IntakeAgeSweep` carries
  `@Scheduled(fixedDelayString = "${yotresultsdistribution.intake.gauge-refresh}",
  scheduler = IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER)` over a `void` method and a body, and
  **no `@SchedulerLock` at all**.
- **Every scheduled method names its scheduler, and the two existing ones gain the attribute too.**
  Declaring three `TaskScheduler` beans routes nothing: Spring's `@Scheduled` processing resolves a
  single scheduler for the context, and with more than one candidate it picks by name or by type and
  falls back to its own. Three beans and no attribute is three schedulers with one of them doing all
  the work - and SC-008's claim that the 07:00 run and the fixed-delay sweep cannot land on the
  thread the 18:00 run is using would be a comment rather than a behaviour. So each of the **four**
  scheduled methods names its bean:
  - `RegisterGenerationJob.run` and `GenerationReconciler.reconcileScheduled` gain
    `scheduler = SchedulingConfig.GENERATION_SCHEDULER`, a **new public constant** on the existing
    configuration holding the name of the **existing** bean, `registerGenerationScheduler`. No bean
    is added, renamed or moved by this; only the routing becomes explicit, and the two generation
    surfaces keep sharing one scheduler exactly as they do today.
  - `ExceptionReportJob.run` names `ReportSchedulingConfig.REPORT_SCHEDULER`.
  - `IntakeAgeSweep.sweepScheduled` names `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER`.
- **The attribute exists on this classpath, checked rather than assumed.** `@Scheduled.scheduler()`
  was added in Spring Framework 6.1. This service is on Spring Boot **4.1.1** (`build.gradle`), whose
  BOM resolves `org.springframework:spring-context` to **7.0.x**; `javap -v` over
  `org/springframework/scheduling/annotation/Scheduled.class` in the resolved
  `spring-context-7.0.9.jar` lists `public abstract java.lang.String scheduler();` beside `cron`,
  `zone` and `fixedDelayString`. **No fallback is needed.** Had the attribute been absent, the
  fallback would have been a `SchedulingConfigurer` per configuration, each registering its own
  triggers against its own `TaskScheduler` - more code, the same guarantee, and a reflection test
  that could no longer read the routing off an annotation.
- **Each of the four is pinned by reflection**, in the suite that owns the class, exactly as
  `GenerationReconcilerTest.ItsOwnSchedule.reconciliation_is_scheduled_independently_of_the_flag_gate`
  and `RegisterGenerationJobTest.WhenItRuns.job_is_scheduled_in_europe_london` already read `cron`,
  `zone` and `@SchedulerLock` off the method. An attribute nobody asserts is an attribute a later
  edit removes.
- **Rationale**: the lock duration is written once, as the setting that states it, because that is
  what `RegisterGenerationJob.LOCK_AT_MOST_FOR` already does and a literal in an annotation is the
  relationship between `lock-at-most-for` and the run budget written down twice. The `void` wrapper
  is `GenerationReconciler.reconcileScheduled`'s shape: ShedLock's interceptor refuses to lock a
  method returning a **primitive** (`LockingNotSupportedException`, raised on every call through the
  proxy), so a scheduled entry point that stays `void` cannot be broken later by a body whose return
  type changes, and the body stays directly callable by the CLI and by a unit test without going
  through the proxy at all. `RunCorrelation.under(...)` is what gives the run its `runId` and,
  crucially, takes it away again: the scheduler's threads are pooled, and an id left behind would be
  inherited by whatever ran next on that thread (constitution Principle VII, v3.1.0).
- **The sweep is not locked, deliberately.** A gauge describes the JVM that publishes it. Lock the
  sweep and one replica reads the store while the others publish whatever they last saw, so a
  two-pod deployment shows one pod's view under two pod labels and the alert is a coin toss over
  which pod won the lock. Unlocked, every replica refreshes its own two gauges from the same store,
  every reading is true of the JVM that reports it, and **an alert on either gauge must aggregate
  across pods with `max()`** - which is the standard shape for a per-pod age gauge and is stated
  here so the operability story writes the rule down rather than discovering it. The cost is one
  cheap read per pod per interval: a `LIMIT 1` and a count over the partial index, on the handful of
  rows still in flight. There is no lock budget to blow and no timeout to set - the query is bounded
  by the index, not by a deadline.
- **Follow-up recorded, not fixed here**: the four batch-age gauges 002 added are refreshed by
  `GenerationReconciler` **under its lock**, so exactly one pod carries them and the other replicas
  publish stale or zero readings for the same names. That is the same defect in the other direction
  and it deserves the same treatment (refresh per JVM, alert with `max()`), but it is 002's code and
  changing it is not this increment's business: it is logged here as a follow-up for the operability
  story, alongside the alert rules those gauges still lack.
- **Note recorded rather than acted on**: `RegisterGenerationJob.run()` returns `RunReport` under
  `@SchedulerLock` and works, because `RunReport` is not a primitive. The `void` wrapper here is
  therefore a shape choice rather than a ShedLock requirement for this particular body; it is taken
  because the two scheduled surfaces in `batch/` should read the same way and because the body's
  return type is the report the CLI wants, not something a schedule has anybody to hand back to.
- **Alternatives**: one lock shared with the generation run, rejected - the 07:00 report would then
  be blocked by an 18:00 run that overran, and SC-008 asks the opposite. A `@Scheduled` with no lock
  **for the report**, rejected - a two-replica deployment would e-mail support twice. The sweep is
  the opposite case and takes the opposite answer: it sends nothing and writes nothing, so running
  everywhere costs a read and buys a true reading per pod.

## 3. Log Analytics output - structured arguments on the existing stdout stream

- **Decision**: two events **written by the sink**, and one flat run line written by the **job**.
  `yotresultsdistribution_exception_report`, once per run, carries **ten** fields: `event`, `run_id`,
  `window_from`, `window_to`, `snapshot_at` and the five counts `request_failed`, `request_late`,
  `batch_late`, `batch_failed` and `notification_failed`. And `yotresultsdistribution_exception`, once
  per exception, carries `event`, `run_id`, `kind`, and then the applicable subset of the entry's
  thirteen components **including `kind`** - `source`, `request_id`, `hearing_id`, `hearing_day`,
  `batch_id`, `notification_id`, `court_centre_id`, `register_date`, `status`, `attempts`, `reason`,
  `age_seconds` - **only the fields applicable to the kind, the others absent** rather than present
  and null, so a KQL `isnotempty()` means what it says. Ten summary fields and thirteen entry
  components are the numbers, stated here and in data-model.md and nowhere else, so there is one
  place to change them. Both are
  written as structured arguments, through
  `net.logstash.logback.argument.StructuredArguments.value(...)`, inside
  `LogEventReportSink`, and both `src/main/resources/logback.xml` and
  `src/main/resources/logback-cli.xml` gain an `<arguments/>` provider so the encoder emits those
  arguments as top-level JSON fields. Container Insights carries the pod's stdout into
  `ContainerLogV2`; KQL reads the fields off the parsed JSON.
  `TelemetryPrivacyTest.ShippedConfiguration` is extended to require the provider in **both** files,
  beside the `<mdc/>` claim it already makes, because a command's lines reach the same index.
- **The sink states no delivery outcome, because it cannot know one.** The earlier shape had the
  summary event carry `delivered_log` and `delivered_email`, which asked one sink to announce how
  both had gone - while the other may not have been asked yet, and while this one cannot observe its
  own write reaching an index. A log line claiming a delivery nobody observed is worse than no claim
  at all; it is the same failure as a run report that tallies a night still settling. So the summary
  event says only what the **report** found, and how the report **travelled** is the run's own fact.
- **`ExceptionReportJob` writes one `exception_report_run` line, after every sink has returned**, in
  the flat `key=value` shape `RegisterGenerationJob.recorded` already uses for
  `event=register_generation_run`:
  `event=exception_report_run run_id=... window_from=... window_to=... entries=N
  delivered_log=ok|failed delivered_email=ok|failed|skipped|disabled
  outcome=delivered|partial|failed duration_ms=...`. It is flat rather than structured for the
  reason the run report is: one line per run, read by eye and by a single-field filter, not a row a
  saved query aggregates. `delivered_email` distinguishes `skipped` (the command was run without
  `--email`) from `disabled` (the sink is not on the context at all), because "nobody asked" and
  "nobody could" are different operational facts. `ReportExceptionsCli` prints the **equivalent** as
  its last line, so an on-demand run says the same four things about its delivery as the 07:00 one.
- **Rationale**: the platform's own log collection is already shipping every line this service
  writes; SC-003 asks only that the fields be individually queryable, which is a question about the
  encoder rather than about a new pipeline. The `<arguments/>` provider is the one line of
  configuration that turns `value("request_id", id)` from message text into a field, and
  `logstash-logback-encoder` is already the only encoder either file declares, so nothing is added to
  the build.
- **Alternatives rejected**:
  - **The Azure Monitor Logs ingestion API.** A second delivery path with a second identity, a Data
    Collection Rule and a Data Collection Endpoint for this service to own, and a new failure mode
    (events that reached neither the API nor the log) for output that the container log already
    carries reliably.
  - **OpenTelemetry with the Azure Monitor exporter.** The same second path and the same DCR
    ownership, plus an SDK and an exporter on the classpath, to emit what is already emitted.
  - **MDC-only fields.** MDC is thread-local and per-line: a run writing one event per exception
    would set and clear a dozen keys around every line, and a key left behind rides the next line of
    the same run as a field that looks true. That is a misuse of a correlation slot, and it is the
    same argument `RunCorrelation` makes about the pooled scheduler threads.
  - **A flat `key=value` message, like the run report's line.** Every query would need `parse()`,
    which is exactly the field parsing SC-003 forbids. The run report keeps its form because it is
    one line a night read by eye; this is dozens of lines read by a saved query.

## 4. E-mail output - CSV into the file service, one send per recipient

- **Decision**: `EmailReportSink` writes the exception list as a CSV through a **new** port method
  `PayloadFileStore.storeText(UUID fileId, String text, PayloadMetadata metadata)` and then asks a
  **second new port**, `application/ReportMailer`, for one send per configured recipient:

  ```java
  public interface ReportMailer {
      MailOutcome send(ReportMail mail);
  }

  public record ReportMail(UUID notificationId, UUID templateId, String sendToAddress,
                           UUID fileId, Map<String, String> personalisation) { }
  ```

  Its adapter is `adapter/notificationnotify/NotificationNotifyReportMailer`, which reuses the
  existing client's HTTP shape exactly: `POST
  /notificationnotify-command-api/command/api/rest/notificationnotify/notifications/{notificationId}`,
  media type `application/vnd.notificationnotify.email+json`, `CJSCPPUID` identity, body exactly
  `templateId`, `sendToAddress`, `fileId`, `personalisation`, **202 and nothing else** is success,
  a 4xx is never retried, 408/429/5xx are transient.
- **Why a second port and a second adapter rather than reusing `RegisterNotifier`.** The existing
  port is `NotificationOutcome send(RegisterNotification notification, UUID documentFileId, ...)`:
  its argument is a **batch's notification row**, and `NotificationNotifyClient`'s private
  `SendEmail` record serialises a `Personalisation(String yotsName)` and nothing else. A report
  e-mail has no notification row, no Youth Offending Team and no `yotsName`; bending the register's
  port around it would mean nullable arguments on the path that e-mails Youth Offending Teams about
  real registers, which is the one path in this service that must not acquire an "unless it is a
  report" branch. So the report gets its own narrow port, and the **HTTP shape is shared rather than
  copied**: a package-private request builder in `adapter/notificationnotify/` composes the URI, the
  media type, the identity header and the classification once, and both adapters call it. The
  register path's behaviour, its tests and its vendored-schema assertions are untouched and stay
  green.
- **The vendored schema permits the counts in the body, checked rather than assumed.** In
  `specs/002-consolidate-progression-leg/contracts/notificationnotify/notificationnotify.email.json`
  the body is `"additionalProperties": false` with `templateId` and `sendToAddress` required - but
  the `personalisation` property is itself `"type": "object"` with an empty `properties` block and
  **`"additionalProperties": true`**. The sibling
  `notificationnotify.command.send-email-notification.json` spells it identically, with
  `notificationId` added as a required property because that copy is the command envelope rather
  than the body. So arbitrary personalisation keys are contract-legal, and **the counts and the
  window travel in `personalisation`** as FR-006 and acceptance scenario 4.4 ask. No wording in the
  spec has to weaken: the body carries the summary, the CSV carries the detail.
- **Both halves write a file, so the port is behind an "either half" condition.** Review gate 7
  found the consequence of not saying so: the LIVE and STUB `PayloadFileStore` beans and the second
  datasource under them were declared behind `yotresultsdistribution.generation.enabled`, so a pod with the
  generation half off and this output on held no store, no pool, and could not start - the FR-004
  deployment this output exists for. They are in `config/FileServiceConfig` and
  `config/FileServiceDataSourceConfig` behind `config/FileServiceNeeded` now, which answers
  `yotresultsdistribution.generation.enabled` **or** `yotresultsdistribution.report.email.enabled`. An OR is what a
  shared downstream needs and is what `@ConditionalOnProperty` cannot express, which is why it is a
  `Condition` class. It is **not** a second cutover lever: neither setting decides which
  implementation is live, and the one lever is still the `YotResultsDistributionService` flag the nightly job
  reads. `PropertiesValidator` follows the same sentence - `yotresultsdistribution.fileservice.url` is
  required by whichever half writes a file, and the refusal names the half that asked. Nothing
  generation-only moved: the renderer, the notifier and the flag reader are chosen where they were.
- **The file-service write is permitted.** The design owner ruled on 2026-09-14 that the framework
  file service is the one store outside this service's own that may be written directly (design Q20,
  closed). The ruling is **already written down**: the consumed-contracts row "the framework
  file-service `metadata` + `content` table schema" in `.claude/rules/design_rules.md` carries it
  verbatim as of commit `94bd245` on `main`, so nothing has to be added there for this increment to
  be permitted. What the design-rules sync task (T069) adds to that row is narrower: that the CSV attachment is a **second**
  write through the same pinned contract, so a reader of the rules knows there are now two callers
  and not one. The
  write is the same two inserts in the same order as `FileServicePayloadStore.store`, because
  `metadata.file_id` is a foreign key onto `content.file_id`: content first, then metadata. The
  content column is `bytea` and takes the CSV's UTF-8 bytes. The metadata row carries the same five
  keys progression spells: `fileName` = `court-register-exceptions_{yyyy-MM-dd}.csv`,
  `conversionFormat` = `csv`, `templateName` = `yot-results-distribution-exception-report`, `numberOfPages` = 1,
  `fileSize` = the byte count.
- **Ids before calls.** The `fileId` is minted and written into the run's log line **before** the
  file-service write, so an attachment that exists under an id nothing recorded is impossible - the
  same discipline 002 applies to `payload_file_id` before `generate-document`.
- **Recipients and template are configuration, never code.**
  `yotresultsdistribution.report.email.recipients` is comma-separated from `${YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS:}`
  and `yotresultsdistribution.report.email.template-id` from `${CR_REPORT_TEMPLATE_ID:}`, both supplied per
  environment from Key Vault through the CSI driver with workload identity, never as a chart value
  and never with a default in this repository: they are people's addresses.
- **Personalisation** carries the counts and the window **as strings** (Notify substitutes text),
  which is what lets the body say "nothing was wrong" as readily as it says five numbers (FR-012,
  scenario 4.4). Every value in the map is a count, a window boundary or a bounded code: no
  identifier, no address and no free text another system wrote is ever a personalisation key or
  value, so the privacy sweep has the same claim over the body it has over the events.
- **Masking lives where an address does.** The e-mail sink is the one place an address is in hand,
  and every line it writes shows `***` for the local part the way `ListBatchesCli.masked` does.
  Everywhere else there is nothing to mask: no read this feature makes selects
  `register_notification.email_address`, so no address appears in either event, in the CLI table or
  in the CSV.
- **The template is a cross-team dependency and it gates User Story 4 alone.** The Notify template is
  owned by the notificationnotify team. Until it exists, `yotresultsdistribution.report.email.enabled` stays
  false, stories 1, 2, 3 and 5 ship and are complete, and the `--email` flag is refused with a
  bounded reason rather than silently doing nothing.
- **Alternative rejected**: a body-only e-mail with the exceptions inline. Notify's body has a
  length limit a bad morning would exceed, it renders no table, and truncating the list is precisely
  the silence this service exists to end. It is retained only as the documented fallback the spec's
  third assumption names, if the file-service write were ever refused.

## 5. Configuration and its refusals

- **Decision**: `config/ReportProperties`, a record bound at `@ConfigurationProperties(prefix =
  "yotresultsdistribution.report")`, mirroring `GenerationProperties`' style and its `@DefaultValue`s:
  `enabled=false`, `cron="0 0 7 * * MON-FRI"`, `zone="Europe/London"`,
  `zoneOverrideAcknowledged=false`, `lockAtMostFor=15m`, `requestTerminalWithin=30m`,
  `batchGeneratedWithin` (no default), `notifiedWithin=15m`, `email.enabled=false`,
  `email.templateId`, `email.recipients`. The gauge-refresh interval is **not** here: it binds on
  `YotResultsDistributionProperties` as a nested `Intake(gaugeRefresh)` record at
  `yotresultsdistribution.intake.gauge-refresh`, with its own `@DefaultValue("10m")`. Every default is
  written a second time in `application.yaml` with a comment saying what breaks without it,
  following the `yotresultsdistribution.generation` block.
- **There is no `window` setting.** The scheduled run's window is
  `ReportWindow.sinceLastScheduledRun(cron, zone, now)`: from the previous occurrence of the
  report's own cron to now. A Monday 07:00 run therefore reads back to Friday 07:00, and every
  FAILED request, batch and notification lands in **exactly one** report - no gap over a weekend, no
  overlap after a schedule change, and nothing for an operator to remember to widen. A duration
  beside a schedule is one fact written twice, and the two are only ever equal until somebody edits
  one of them. The CLI keeps `--since` for the incident case, and an absent `--since` uses the same
  computation, so the bare command answers what the morning run would have answered.
- **One undefaulted duration, and one mechanism each.** The rule is that a duration is either a
  record value resolved once in the validator, or a placeholder with a literal default - never both.
  **How each of the two defaulted durations resolves is written once, in data-model.md's
  "How the two borrowed and defaulted durations resolve" table, and is not restated here or in
  plan.md.** What belongs in this section is only the reason the rendering limit borrows the grace
  period at all: that is already the interval after which the reconciler decides a render has not
  happened, and two different answers to "how long is too long for a render" is the shape that makes
  an alert argue with a batch state.
- **Refusals**, all in `PropertiesValidator` following its existing helpers and each naming the
  offending setting: every duration positive (`request-terminal-within`, `batch-generated-within`
  where it is set explicitly, `notified-within`, `yotresultsdistribution.intake.gauge-refresh`,
  `lock-at-most-for`); the zone rule **identical** to generation's, `Europe/London` unless
  `zone-override-acknowledged=true` and then a zone the JVM knows; `lock-at-most-for` at least
  `REPORT_RUN_BUDGET` (5m, fixed) plus the **existing** `SCHEDULER_LOCK_MARGIN` (10m), which is what
  the shipped 15m is - the class already holds that constant for generation's 60m + 10m = 70m, and a
  second constant of the same value under a second name is a margin that can drift from itself;
  `email.enabled=true` requiring a non-blank, well-formed template UUID **and** at least one
  recipient, each refusal naming its setting; every recipient parsing as an address.
- **Rationale**: these are the errors a healthy-looking pod hides - a zero threshold that reports
  everything as late, a schedule read in UTC that fires at 08:00 through the summer, an e-mail output
  enabled with nobody to send to - and every one of them is discovered at 07:00 the next morning if
  it is not discovered at startup (research section 11 of 002 makes the same argument for the
  generation half).
- **Alternatives**: a runtime skip when the e-mail output is misconfigured, rejected - a deployment
  that sends nothing every morning and says so only in a log line is the failure mode the spec's
  scenario 4.3 asks to be refused at startup instead.

## 6. Metrics - the four instruments design section 11 promised

- **Decision**: in `config/ProcessingMetrics`, beside the instruments already there:
  - gauge `yotresultsdistribution_oldest_non_terminal_request_age` (seconds), **registered at zero in the
    constructor**;
  - gauge `yotresultsdistribution_non_terminal_requests_over_threshold`, likewise;
  - timer `yotresultsdistribution_request_duration`, tagged `outcome` from the terminal statuses
    (`completed`, `failed`) and by nothing else;
  - counter `yotresultsdistribution_exception_report_runs_total{outcome}`, whose `outcome` is
    `delivered`, `partial` or `failed` - the same word the `exception_report_run` line carries;
  - counter `yotresultsdistribution_exception_report_deliveries_total{sink,outcome}`;
  - counter `yotresultsdistribution_exceptions_reported_total{kind}`;
  - counter **`yotresultsdistribution_intake_sweep_failures_total{reason}`**, the sweep's absorbed refusal.
    A reading that cannot be taken is counted here rather than rethrown, for the reason section 1
    gives: it is the design rules' "one absorbed refusal is telemetry" clause, and this is the
    counter that makes the absorption visible. A path that drops something must move a counter.
  Naming and style follow `GenerationMetrics` - a `public static final String` per name, an
  `AtomicLong` or `AtomicInteger` behind each gauge, `Gauge.builder(...).register(registry)` in the
  constructor.
- **The two gauges are per JVM, and an alert must say so.** Nothing locks the sweep (section 2), so
  every replica publishes its own reading of the same store. An alert on
  `yotresultsdistribution_oldest_non_terminal_request_age` or
  `yotresultsdistribution_non_terminal_requests_over_threshold` therefore aggregates across pods with
  `max()`, which is the honest reading: the oldest unfinished request is the oldest any pod can see.
  **The same is true, by accident rather than design, of the four batch-age gauges 002 added**: the
  reconciler refreshes them under its lock, so one pod carries them and the rest publish nothing
  useful under the same names. That is recorded here as a follow-up for the operability story, not
  fixed in this increment - it is 002's code, and a gauge changing its refresh discipline is a change
  to a leg this increment does not otherwise touch.
- **The timer is started and stopped through an opaque token.** `ProcessingMetrics` answers a
  `ProcessingMetrics.Timing` where a run is admitted and takes that token back with the terminal
  outcome, so `application/DistributionPipeline` holds a token and imports **no** Micrometer type.
  `Timer.Sample` is a Micrometer class, and a `Timer.Sample` field on the pipeline would put the
  metrics library in the application layer for the sake of two lines (constitution Principle V,
  which is also why `StructuredArguments` lives in one adapter).
- **Where the timer is recorded, exactly.** `DistributionPipeline` already has the two places a
  request reaches a terminal state and the guard has **accepted** the write: `settled(...)`, which
  is where `metrics.requestSettled(RequestOutcome.COMPLETED)` and `metrics.completed(reason)` fire
  under `outcome instanceof GuardDecision.Complete`, and `parked(...)`, which is where
  `metrics.requestSettled(RequestOutcome.FAILED)` fires under `outcome instanceof
  GuardDecision.DeadLetter`. A `Timer.Sample` is started where the guard admits the run
  (`GuardDecision.Run`) and stopped in those two methods with the terminal outcome as the tag. Both
  places already refuse to count a write the guard rejected, which is precisely the behaviour the
  timer needs: a superseded runner's completion affects no rows and must not contribute a sample.
- **The gauges are registered at construction, not on first use** (acceptance scenario 2.1). A gauge
  that appears only after the first incident is not an alerting surface, which is the argument
  `ProcessingMetrics` already makes for `yotresultsdistribution_intake_suspended`.
- **Labels stay bounded.** `sink` is `log`/`email`, `outcome` is a closed set, `kind` is the **five**
  exception kinds. A request id, hearing id, court centre id or recipient address is never a label -
  that is both a cardinality explosion and, on a register whose every defendant is a youth, a privacy
  breach (constitution Principle VII).
- **Assumption recorded**: the timer measures **from the guard's admission of the delivery the run
  was made on** to the terminal transition, using a monotonic in-process sample, rather than from the
  row's `created_at`. That keeps the V1 single-time-authority rule intact - no JVM reading is
  compared against a stored timestamp - and the wall-clock answer the spec's "receipt to terminal"
  phrase also wants is carried by the report's own `age_seconds`, which the database computes. The
  two together answer both questions; one instrument could not.
- **Queue depth and dead-letter depth stay absent**, as the design says and as
  `ProcessingMetrics`' own javadoc already records: they are read from Azure Monitor's native queue
  metrics, and this service counts the dead-letters it performs, which is a different question.

## 7. Persistence - three reads on the intake half, one on the notifications, four on the batches, one on the register store

- **Decision**: `ProcessedRequestRepository` gains
  `List<ProcessedRequestSummary> failedSince(Instant)` and
  `List<ProcessedRequestSummary> nonTerminalOlderThan(Instant)` (`status IN ('RECEIVED','RETRYING')`
  and `created_at < ?`, oldest first), plus `Optional<ProcessedRequestSummary> oldestNonTerminal()`
  for the sweep's first gauge. `RegisterNotificationRepository` gains `failedSince(Instant)`.
  `RegisterBatchRepository` gains **four** reads of its own - `latePending(Instant)`,
  `lateGenerating(Instant)`, `lateGenerated(Instant)` and `failedSince(Instant)` - each answering a
  `List<BatchException>` and each computing `age_seconds` in SQL. `RegisterStore` gains
  `recordedUnbatchedBefore(Instant)`, answering `List<RecordedRegisterSummary>`, likewise with the
  age in SQL. All five select `failure_reason` where they select a reason at all and **never**
  `sdg_reason`: a column that is never read cannot reach a line, a label or the CSV, which is the
  same construction that keeps `email_address` out of the notification read.
- **Why the batch and register reads are new rather than 002's.** The obvious economy would be to
  reuse `pendingSince`, `generatingSince`, `generatedSince` and `activeUnbatched()`, which already
  answer exactly the rows the three `BATCH_LATE` sources want. They answer **entities** -
  `RegisterBatch` and `RegisterRecord` - and an entity carries timestamps, not an age. Deriving the
  age from one in the JVM means subtracting a stored timestamp from a JVM reading, which is precisely
  the cross-clock comparison the intake reads go to the trouble of avoiding, and it would leave the
  report with two kinds of age: three computed by Postgres and four computed by whichever pod won
  the lock. So every kind's age comes from the same place, and the four entity reads 002 built keep
  their existing callers in the generation leg, unchanged, unrenamed and unwidened.
- **`BatchException`** is the batch projection: `batchId`, `courtCentreId` (a `UUID`, as on
  `RegisterBatch` and `CourtCentreDay`), `registerDate`, `status`, `failureReason`, `attempts` and
  `ageSeconds`. It has no `sdgReason` component at all, so there is nowhere for another system's
  free text to be put even by accident. **`RecordedRegisterSummary`** is the register projection:
  `outputId`, `hearingId`, `courtCentreId`, `registerDate`, `registerTime` and `ageSeconds`.
- **`recordedUnbatchedBefore` shares `activeUnbatched()`'s predicate**, written once in
  `JdbcRegisterStore`, because two spellings of "active, unsuperseded, unbatched and recorded while
  the flag was on" are two answers waiting to disagree. What the new read adds is the `register_time`
  cut-off and the SQL age.
- **`ProcessedRequestSummary`** is a new domain record: `source`, `requestId`, `hearingId`,
  `hearingDay`, `status`, `attempts`, `failureReason`, `createdAt`, `updatedAt`, and `ageSeconds`.
  The age is on the record because the statement is what computes it (below).
- **`FailedNotification`** is the equivalent projection for the notification read, carrying the
  notification id, its batch id, that batch's court centre id and register date, the response code,
  the attempt count, `sentAt` and `ageSeconds`. It exists so the read answers in one statement rather
  than one per row: `RegisterNotification` carries neither its batch's court centre nor an age, and
  looking each batch up would be N+1 reads on a morning where a lot went wrong.
- **Recorded-but-unbatched registers.** The fourth `BATCH_LATE` source is
  `RegisterStore.recordedUnbatchedBefore(Instant)`, given the most recent scheduled generation run,
  which `domain/LastScheduledRun` computes from `yotresultsdistribution.generation.cron` read in
  `yotresultsdistribution.generation.zone`. A register recorded while the flag was OFF is already excluded,
  because the shared predicate excludes it by construction, and that is exactly the spec's rule:
  those are the existing review command's concern and not exceptions.
- **Ages are computed by the database.** Every statement returns
  `extract(epoch from (now() - <the column>))::bigint AS age_seconds`, so no JVM clock reading is
  compared against a stored timestamp and two pods reading the same row agree (the rule V1's header
  comment states). The *cut-off* parameters are a different thing: a window boundary is a moment the
  caller chose, and passing it in is not a comparison of clocks but a statement of what was asked
  for.
- **Migration** `V4__processed_request_report_indexes.sql`, additive and forward-only, never edited
  once applied:
  `CREATE INDEX idx_request_non_terminal_created ON processed_request (created_at) WHERE status IN
  ('RECEIVED','RETRYING')` and
  `CREATE INDEX idx_request_status_updated ON processed_request (status, updated_at)`.
- **Rationale**: the partial index is what makes the sweep's read, which runs every ten minutes for
  the life of every pod, an index scan over a handful of rows rather than a sequential scan over
  every request the service has ever seen; the second serves both `failedSince` reads and the
  support query "what failed yesterday". Neither adds a column, so nothing about the existing state
  machine or its constraints moves.
- **Noted, not resolved**: `register_batch`'s five timestamps and `register_notification.sent_at` are
  written from the JVM by the code that settles the row, not defaulted to `now()` by the database,
  so the ages computed for `BATCH_LATE` and `NOTIFICATION_FAILED` are the database's `now()` minus a
  timestamp a pod wrote. Only `processed_request.created_at` and `updated_at` default to `now()`.
  The difference is at most a pod's clock skew and it affects a reported age, never a decision about
  a claim, so it is recorded here rather than changed - a migration that rewrote those defaults would
  be a change to the 002 state machines for the sake of a number on a report.

## 8. Scheduling wiring - the split, and what must keep passing

- **Decision**: `@EnableScheduling`, `@EnableSchedulerLock` and the `LockProvider` bean move out of
  `config/SchedulingConfig` into a new `config/SchedulingInfrastructureConfig`, conditional on
  **nothing but not being a command JVM** (`@Conditional(CliModeConfig.NotCliMode.class)`, plus the
  `@Profile("!test")` every database-touching configuration here carries) - no enabled-flag condition
  at all. `SchedulingConfig` keeps the generation `TaskScheduler` and the `RegisterGenerationJob`
  bean under its existing conditions. A new `config/ReportSchedulingConfig`, conditional on
  `yotresultsdistribution.report.enabled` and not CLI, declares its own single-thread `TaskScheduler` named
  `exception-report-` and the `ExceptionReportJob` bean. A new `config/IntakeSweepConfig`,
  conditional on **not CLI and the non-test profile only**, declares its own single-thread
  `TaskScheduler` named `intake-sweep-` and the `IntakeAgeSweep` bean.
- **`@EnableSchedulerLock` keeps the default it has today.** It moves verbatim, still written
  `@EnableSchedulerLock(defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR)`. The
  annotation's default is a fallback for a `@SchedulerLock` that states no `lockAtMostFor`, and all
  three locked methods on this context state their own, so the value it carries is inert - which is
  exactly why it is left alone rather than "tidied" to the report's budget on the way past. Changing
  it would be a change to generation's lock semantics made in a task about the report's wiring.
- **Each configuration publishes its scheduler's bean name as a public constant**, so the
  `@Scheduled(scheduler = ...)` attribute names a constant rather than a string literal:
  `SchedulingConfig.GENERATION_SCHEDULER` (`"registerGenerationScheduler"`, the bean that already
  exists), `ReportSchedulingConfig.REPORT_SCHEDULER` and
  `IntakeSweepConfig.INTAKE_SWEEP_SCHEDULER`. A bean name spelled twice is a bean name that can be
  renamed once.
- **Why the infrastructure carries no flag condition.** The sweep must refresh the gauges on every
  service JVM, including one with the report **and** the generation half switched off - that is
  user story 2 scenario 4 in as many words. A condition of generation-or-report would leave exactly
  that pod without `@EnableScheduling` and therefore without the instruments the increment exists
  to add. The condition that does matter is the CLI one, and it is unchanged: a command JVM
  contributes none of the three configurations, so it processes no `@Scheduled` and never joins the
  one-consumer public-event subscription.
- **Two repository beans have to move.** `registerBatchRepository` and
  `registerNotificationRepository` are declared today in `config/GenerationConfig`, which is
  conditional on `yotresultsdistribution.generation.enabled`. The report reads both - `BATCH_LATE` and
  `BATCH_FAILED` from the first, `NOTIFICATION_FAILED` from the second - so with
  `report.enabled=true` and `generation.enabled=false`, the exact FR-004 deployment, the context
  cannot start. Their `@Bean` methods move verbatim to `config/ProcessedLogConfig`, which is
  generation-neutral (`@Profile("!test")` and nothing else) and already declares
  `ProcessedRequestRepository`, `ProcessedOutputRepository`, `registerStore` and the guard over the
  same `JdbcClient` and the same `PlatformTransactionManager` those two constructors take. Nothing
  about either repository changes; only where its bean is declared.
- **Rationale**: FR-004 requires the report to run where the generation half is switched off, and
  today every one of those three declarations sits inside a configuration conditional on
  `yotresultsdistribution.generation.enabled`, so an intake-only pod processes no `@Scheduled` at all. Spring
  permits one `@EnableScheduling` and one `LockProvider` per context, so they have to move up rather
  than be repeated. A scheduler of the report's own, single-threaded, is what keeps SC-008 true: the
  07:00 run and the fixed-delay sweep cannot land on the thread the 18:00 run or the reconciler is
  using.
- **What must keep passing**, read from `config/SchedulingConfig` and `config/CliModeConfigTest`:
  a CLI JVM runs **no** scheduled task. `CliModeConfig`'s javadoc is explicit that the conditional
  goes on the configuration rather than the bean because `SchedulingConfig` owns `@EnableScheduling`
  as well as the job, so after the split the CLI condition must be on **all three**:
  `SchedulingInfrastructureConfig` (which now owns the annotation), `ReportSchedulingConfig` and
  `IntakeSweepConfig`. It is the only condition two of the three have, which is exactly why it must
  be right. `SchedulingConfig` keeps its own condition so an intake-only pod still contributes no
  generation job. `CliModeConfigTest` is extended to assert the absence of all three new
  configurations in CLI mode, and the existing assertions about the consumer and the public-event
  listener are untouched. `RegisterGenerationJobTest` and `GenerationReconcilerTest` each gain one
  reflection case for the `scheduler` attribute their method now names, beside the `cron`, `zone`
  and `@SchedulerLock` cases they already read off it; nothing else about either suite moves.
- **Alternatives**: making the report depend on `generation.enabled`, rejected in Complexity
  Tracking. A second `LockProvider` in `ReportSchedulingConfig`, rejected - two providers over one
  `shedlock` table is a race dressed as configuration. Declaring a second copy of the two moved
  repositories rather than relocating them, rejected for the same reason: two beans of one
  repository over one table is the same race with a different name.

## 9. The operations command

- **Decision**: `batch/cli/ReportExceptionsCli`, the sixth command, `report-exceptions`. `Args` gains
  a `SINCE` option and an `EMAIL` flag and lists both in `NAMES`; `permits` keeps them off the other
  five commands. It is added to `CliMain.COMMANDS` and `CliMain.registryOf`, to the `case` pattern
  and the `CLI_COMMANDS` string in `docker/startup.sh` (the two lists are one list, and
  `CliDispatchIT` asks the built image for both halves), and to `TelemetryPrivacyTest`'s
  operator-token group.
- **`--since` parse rules** (data-model.md carries the table):
  an ISO-8601 instant (`2026-09-14T06:00:00Z`) is the window's `from`; an ISO-8601 duration
  (`PT2H`) or the shorthands `<n>d`, `<n>h`, `<n>m`, `<n>s` (`2h`, `30m`) are subtracted from now;
  absent, the window reaches back to the previous scheduled report time, exactly as the 07:00 run
  computes it, so the bare command answers what the morning run would have answered. The window's
  `to` is always now.
  Anything else is refused as `unreadable-argument` naming `--since` and **never quoting the token
  typed**, because an operator's terminal is pasted into tickets and the argument may hold anything.
- **`--email`** is refused with a `declined` (exit code 1, the code the five existing commands use
  for a refusal) when `yotresultsdistribution.report.email.enabled` is false, and the line says which setting
  it is. Nothing is written in that case.
- **No flag, and no `--ignore-flag`.** The report reads and writes nothing the cutover decides, so
  the `YotResultsDistributionService` flag is not read at all and there is nothing to override. That is not a
  second lever appearing: it is a command that is not on the lever's circuit.
- **Which sinks the command uses.** It calls `build(window, runId)` and then
  `deliver(report, sinks)` with the sinks **it** chose: the log sink always, and the e-mail sink only
  where `--email` was given and accepted. It does not deliver to every sink on the context, because a
  command that e-mailed support whenever it was run would make an incident's third invocation an
  incident of its own.
- **The command opens its own correlation.** `RunCorrelation.under(...)` wraps the command's work and
  `RunCorrelation.current()` is what it passes into `build(...)`, so the events and lines an
  on-demand report writes carry a run id exactly as the 07:00 run's do (FR-011). That makes
  `RunCorrelation` visible to `batch/cli` as well as `batch/`; it is the only change to that class,
  and it changes no behaviour.
- **Output**: one bounded line per exception, oldest first; then a counts line carrying the five
  counts and the window; then, **always**, the equivalent of the job's `exception_report_run` line -
  `run_id`, the window, `entries`, `delivered_log`, `delivered_email` (`skipped` without `--email`,
  `disabled` where the sink is not on the context) and `outcome` - written **after every sink has
  returned**, so the command says the same four things about its delivery that the 07:00 run does.
  The command **exits 2** ("could not") when any sink it asked failed, so a shell knows without
  reading the line; it exits 0 when every sink it asked said ok, and 1 for a refusal. **No recipient
  address appears at all** - not masked, absent: no read this feature makes selects one, so a
  `NOTIFICATION_FAILED` line names its notification id, its batch and its response code. Masking
  survives only in the e-mail sink's own lines, where an address genuinely flows. No defendant data,
  no free text from a downstream.
- **Rationale**: this is the surface support already uses (`kubectl exec ... -- ./startup.sh
  <command>`), it runs with the pod's own identity and network path, and it needs no data-plane
  credential of its own (research section 13 of 002).

## 10. Privacy, and how it is proved

- **Decision**: `support/GenerationLegs.THE_LEGS` gains `ExceptionReportJob`, `IntakeAgeSweep`,
  `ExceptionReportService`, `LogEventReportSink`, `EmailReportSink`,
  `NotificationNotifyReportMailer` and `ReportExceptionsCli`, and
  `driveEverything()` drives each of them so **every** LOG statement in the seven is reached with
  markers wherever a person could be named. The mailer is on the list because it is the one class
  besides the sink that holds an address, and a class added to `THE_LEGS` but not driven is a class
  the sweep silently does not cover. `TelemetryPrivacyTest` then sweeps the capture, at TRACE,
  including the rendered text of any attached exception. The operator-token group gains
  `report-exceptions`. `LogStatementSweepTest` keeps its existing claim: no throwable this service
  did not write is attached anywhere in `src/main`, so a caught failure is named by class.
- **Rationale**: a claim about every line this feature can write cannot be made from a sample, and
  the fixture is already the mechanism - a class added to `THE_LEGS` but not driven would be a class
  the sweep silently does not cover.
- **Reasons stay bounded.** `ReportDeliveryReason` and every `reason` field on an event is a code
  from a closed enumeration, never raw exception text, never a fragment of a downstream's body, never
  a status line quoted back. `BATCH_FAILED` is the case that makes this concrete: the row beside its
  bounded `failure_reason` holds `sdg_reason`, systemdocgenerator's free text about a document, and
  the read simply does not select it.
- **No address to mask on the report's own paths.** `register_notification.email_address` is in no
  select list this increment writes, so the CLI table, both events and the CSV have nothing to mask
  and nothing to leak. The masking rule still applies where an address is genuinely in hand: the
  e-mail sink's lines about the recipients it is sending to.

## 11. Assumptions taken where the decisions did not reach

1. **`notification_id` on the exception event - resolved, no longer an assumption.** This was
   recorded as an assumption when FR-002's field list was read as not naming it. It does: FR-002
   lists "batch id, notification id" among the fields an exception may carry, and acceptance
   scenario 1.4 requires a `NOTIFICATION_FAILED` exception to name "the batch and the notification".
   The event therefore carries `notification_id` for that kind alone, under the same rule as every
   other field - present only where it applies - and it is a requirement rather than an assumption
   taken where the decisions did not reach.
2. **`FailedNotification` as a second new projection record.** The decisions named
   `ProcessedRequestSummary` explicitly; the notification read needs its own projection for the same
   reason (its batch's court centre and its age are not on `RegisterNotification`), so one is added.
3. **`ProcessedRequestSummary` carries `ageSeconds`** in addition to the nine fields named, because
   the age is what the statement computes and carrying it separately would mean a second read.
4. **`ReportSinkName` and `DeliveryStatus`** are introduced as small bounded enums so the `sink`
   metric label and the run's partially-delivered outcome are types rather than strings. The
   decisions named `DeliveryOutcome` and `ReportDeliveryReason`; these are the two bounded values
   that outcome is made of.
5. **`REPORT_RUN_BUDGET` (5m)** is the one new fixed constant behind the "lock-at-most-for at least a
   fixed margin over the expected run" rule; the margin is the **existing**
   `PropertiesValidator.SCHEDULER_LOCK_MARGIN` (10m), reused rather than reintroduced under a second
   name, so the shipped 15m is exactly budget plus margin and mirrors generation's 60m + 10m = 70m
   with the same margin constant. Five minutes is an order of magnitude above SC-006's ten-second
   read budget, which leaves room for the e-mail sends.
6. **The `report-exceptions` command does not take `--email` further than the sink.** It reuses
   `EmailReportSink` rather than composing a second send path, so an on-demand e-mail and a 07:00
   e-mail are the same code and the same recipients; what differs is only which sinks the command
   passes to `deliver(...)`.
7. **`BatchException` and `RecordedRegisterSummary` are projections of their own**, rather than the
   `RegisterBatch` and `RegisterRecord` entities 002 already defines. The earlier pass took the
   opposite view - reuse the entity, derive the age in the JVM from `failedAt` against the run's
   `snapshotAt` - and that was wrong for the reason section 7 now gives: it would compare a stored
   timestamp with a JVM reading, and it would leave one report carrying two kinds of age. The two
   projections carry `ageSeconds` from the same statement that selects the row, which is the rule
   every other kind already follows, and neither has an `sdgReason` component for free text to reach.

## 12. What stays exactly as 001 and 002

The inbound transport, the idempotency guard, the whole transformation chain, the register store and
its supersession rule, the 18:00 generation run, the reconciler, the public-event listener, the
notifying leg, the App Configuration flag and its single reader, the five existing operations
commands, every vendored contract, and every `doc/DEFECT-FIXES.md` row. This increment adds a reader
and two outputs; it changes no behaviour any of the above already has.
