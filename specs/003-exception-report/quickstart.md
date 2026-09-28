# Quickstart: Exception report for production support

## Local dependencies

Unchanged from 002. The `app` service in `docker-compose.yml` already brings the generation half up
against WireMock (systemdocgenerator, notificationnotify and the App Configuration `kv` stub) and the
`fileservice-postgres` fixture, which is what the e-mail output needs: the CSV goes into the same
file service the register PDF does, and the send goes to the same WireMock notificationnotify.

```bash
docker compose up -d postgres servicebus-emulator artemis fileservice-postgres wiremock sdg-echo
```

## Turn the report on

Five environment variables on the `app` service, added to the block 002 wrote. **Four of them
belong to the report** - `YOTRESULTSDISTRIBUTION_REPORT_ENABLED`, `YOTRESULTSDISTRIBUTION_REPORT_EMAIL_ENABLED`,
`CR_REPORT_TEMPLATE_ID` and `YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS`. The fifth,
`YOTRESULTSDISTRIBUTION_INTAKE_GAUGE_REFRESH`, belongs to the **intake half** and is shown here because this
is where a local run meets it. They land in two commits, not one: the report switch and the
gauge-refresh interval with the scheduling phase's compose task, the three e-mail variables with the
e-mail phase's wiring task, because a template id and a recipient list on a service whose e-mail
sink does not exist yet is configuration nothing reads.

```yaml
      # The 07:00 Europe/London weekday report. Independent of YOTRESULTSDISTRIBUTION_GENERATION_ENABLED:
      # the report is a read of this service's own store and must keep running on a pod that
      # generates nothing, which is what makes an intake-only deployment alertable. Its window
      # needs no setting: a run reports from the previous scheduled run to now, so Monday reads
      # back to Friday and nothing falls between two windows.
      YOTRESULTSDISTRIBUTION_REPORT_ENABLED: "true"
      # The intake gauges refresh on this interval in every service JVM that is not a command,
      # whether or not the report or the generation half is enabled, and under no lock: a gauge
      # describes the JVM that publishes it, so alerts aggregate across pods with max(). Ten
      # minutes by default.
      YOTRESULTSDISTRIBUTION_INTAKE_GAUGE_REFRESH: 10m
      # The e-mail output, switched separately from the Log Analytics output. On locally so the
      # WireMock notificationnotify stub is exercised; off in every environment until the
      # notificationnotify team provides the template.
      YOTRESULTSDISTRIBUTION_REPORT_EMAIL_ENABLED: "true"
      # Local-only dummies. Never a real template id and never a real address: in a deployed
      # environment both arrive from Key Vault through the CSI driver.
      CR_REPORT_TEMPLATE_ID: 22222222-2222-2222-2222-222222222222
      YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS: support@example.invalid
```

Startup refuses, naming the setting, if the e-mail output is on with no template or no recipient, if
any threshold, the gauge-refresh interval or the lock is zero or negative, if `lock-at-most-for` is
below the fixed run budget plus `PropertiesValidator.SCHEDULER_LOCK_MARGIN`, or if the zone is not
`Europe/London` without `yotresultsdistribution.report.zone-override-acknowledged=true`. Those are the errors
that otherwise turn up at 07:00 the next morning. There is no window setting to get wrong: the
window is the schedule.

To run it on the host instead of in the container, add the same settings to the `bootRun` block
002's quickstart gives (the gauge-refresh interval can be left at its `10m` default):

```bash
YOTRESULTSDISTRIBUTION_REPORT_ENABLED=true \
YOTRESULTSDISTRIBUTION_REPORT_EMAIL_ENABLED=true \
CR_REPORT_TEMPLATE_ID=22222222-2222-2222-2222-222222222222 \
YOTRESULTSDISTRIBUTION_REPORT_RECIPIENTS=support@example.invalid \
... ./gradlew bootRun
```

## Trigger the report without waiting for 07:00

```bash
docker compose up -d app
curl -s localhost:8082/actuator/health/readiness      # {"status":"UP"}

# the last two hours, to stdout
docker compose exec app /startup.sh report-exceptions --since 2h

# an absolute window start
docker compose exec app /startup.sh report-exceptions --since 2026-09-14T06:00:00Z

# no --since: the same window the 07:00 run would use, back to the previous scheduled run
docker compose exec app /startup.sh report-exceptions

# the same report, also sent to the configured recipients
docker compose exec app /startup.sh report-exceptions --since 2h --email
```

Output is one bounded line per exception, oldest first, then a counts line, then a `delivered=` line
when `--email` was accepted:

```
kind=REQUEST_LATE source=cpp-context-results request_id=... hearing_id=... hearing_day=2026-09-13 status=RETRYING attempts=2 age_seconds=4820
kind=BATCH_FAILED batch_id=... court_centre_id=... register_date=2026-09-12 status=FAILED reason=GENERATION_FAILED age_seconds=55100
kind=NOTIFICATION_FAILED batch_id=... notification_id=... court_centre_id=... register_date=2026-09-12 status=FAILED reason=502 age_seconds=61240
counts request_failed=0 request_late=1 batch_late=0 batch_failed=1 notification_failed=1 batch_released=0 window_from=... window_to=...
event=exception_report_run run_id=... window_from=... window_to=... entries=3 delivered_log=ok delivered_email=ok outcome=delivered duration_ms=412
```

The keys are the **event field names**, spelled exactly as `yotresultsdistribution_exception` spells them -
`request_id`, `batch_id`, `notification_id`, `court_centre_id`, `register_date`, `age_seconds` - so
an operator reading the table and a saved query reading the index are naming the same things. A
field name that differs between the two is a field somebody greps for and does not find.

The **last line is always written**, and it is the command's equivalent of the 07:00 job's
`exception_report_run` line: the same `delivered_log`, `delivered_email` and `outcome` words, written
after every sink has returned - the same `ReportRunOutcome.from` and the same `DeliveryWord`, not a
copy of each. `delivered_email` is `skipped` without `--email` and `disabled` where there is no
e-mail sink on the context at all, which is what `yotresultsdistribution.report.email.enabled=false`
produces, because "nobody asked" and "nobody could" are different facts.

No line carries a recipient address, masked or otherwise: no read this feature makes selects one.
A failed send is named by its notification id, its batch and its response code. Nor does a
`BATCH_FAILED` line carry `sdg_reason` - the bounded `BatchFailureReason` is the reason, and
systemdocgenerator's own words about the document are another system's free text.

Exit codes are the five existing commands': **0** did it, **1** declined, **2** could not. `--email`
against a deployment where `yotresultsdistribution.report.email.enabled` is false is a **decline** (1), and
the line names the setting; nothing is written in that case. An option the command does not accept
is also a decline (1), with the usage line. A sink the command **asked** and that **failed** is a
**2**: the report was built and one of its two audiences did not get it, which is exactly the
`outcome=partial` the last line says, and a shell should not have to read a line to learn it.

**The local stack records no registers**, for the reason 002's quickstart gives: `app` runs with
`YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB`, so a command published to `yotresultsdistribution.requests` completes
`no-defendants` and writes no output row. So `BATCH_LATE` and `BATCH_FAILED` cannot be produced locally
without seeding the store by hand. The kinds together are proved by
`e2e/ExceptionReportEndToEndIT` under
`./gradlew test`. What this block verifies is the half no JUnit suite reaches: that the sixth command
dispatches out of the image, reads the store's own statements, and answers on its three documented
exit codes.

To see `REQUEST_LATE` locally without a hearing, insert a non-terminal row directly:

```bash
docker compose exec postgres psql -U yotresultsdistribution -d yotresultsdistribution -c "
  UPDATE processed_request SET status='RETRYING', created_at = now() - interval '40 minutes'
   WHERE request_id = '<some request id>';"
docker compose exec app /startup.sh report-exceptions --since 2h
```

## Read the events

Every event is a JSON line on the pod's stdout, exactly like every other line this service writes.
The report's two are keyed by their `event` field:

```bash
# every exception from every run
docker compose logs app | jq 'select(.event=="yotresultsdistribution_exception")'

# just this morning's summary
docker compose logs app | jq 'select(.event=="yotresultsdistribution_exception_report")'

# one run, end to end, including the run's own non-event lines
docker compose logs app | jq 'select(.run_id=="<the run id from the summary>")'

# the late requests only, as a table
docker compose logs app \
  | jq -r 'select(.event=="yotresultsdistribution_exception" and .kind=="REQUEST_LATE")
           | [.request_id, .status, .attempts, .age_seconds] | @tsv'
```

If `kind`, `request_id` and the rest appear inside `message` rather than as top-level fields, the
`<arguments/>` provider is missing from `src/main/resources/logback.xml` (or, for a CLI JVM's stderr,
from `logback-cli.xml`). `TelemetryPrivacyTest.ShippedConfiguration` fails on exactly that.

## The KQL support will use

Container Insights carries the pod's stdout into `ContainerLogV2`, whose `LogMessage` is the parsed
JSON line, so every field above is addressable without `parse()`:

```kusto
// this morning's exceptions, newest run first
ContainerLogV2
| where TimeGenerated > ago(1d)
| where LogMessage.event == "yotresultsdistribution_exception"
| project TimeGenerated,
          runId        = tostring(LogMessage.run_id),
          kind         = tostring(LogMessage.kind),
          requestId    = tostring(LogMessage.request_id),
          hearingId    = tostring(LogMessage.hearing_id),
          batchId      = tostring(LogMessage.batch_id),
          courtCentre  = tostring(LogMessage.court_centre_id),
          status       = tostring(LogMessage.status),
          attempts     = toint(LogMessage.attempts),
          reason       = tostring(LogMessage.reason),
          ageSeconds   = tolong(LogMessage.age_seconds)
| order by TimeGenerated desc, ageSeconds desc
```

```kusto
// did the report run, and what did it find - the summary event, twelve fields since 004
ContainerLogV2
| where TimeGenerated > ago(7d)
| where LogMessage.event == "yotresultsdistribution_exception_report"
| project TimeGenerated,
          runId            = tostring(LogMessage.run_id),
          windowFrom       = todatetime(LogMessage.window_from),
          windowTo         = todatetime(LogMessage.window_to),
          snapshotAt       = todatetime(LogMessage.snapshot_at),
          requestFailed    = toint(LogMessage.request_failed),
          requestLate      = toint(LogMessage.request_late),
          batchLate        = toint(LogMessage.batch_late),
          batchFailed      = toint(LogMessage.batch_failed),
          notificationFailed = toint(LogMessage.notification_failed),
          // increment 004's sixth kind, and informational: a batch a run gave up on whose
          // registers went out the same night. Counted here so the counts still add up to the
          // yotresultsdistribution_exception events a query finds
          batchReleased      = toint(LogMessage.batch_released),
          // dropped LATE entries only - the cap never drops a failure, so a shortfall on
          // request_failed, batch_failed or notification_failed is a sink that broke
          truncated          = toint(LogMessage.truncated)
| order by TimeGenerated desc
```

**The summary event says nothing about delivery**, deliberately: it is written by a sink, and a sink
can observe neither its own arrival in this index nor the other sink's. How the report *travelled*
is on the job's own run line, which is a flat `key=value` line like the 18:00 run's rather than a
structured event, so it is read with `parse()` or by a substring filter:

```kusto
// how each morning's report was delivered, and how long it took
ContainerLogV2
| where TimeGenerated > ago(7d)
| where LogMessage has "event=exception_report_run"
| parse tostring(LogMessage) with * "run_id=" runId:string " "
                                   * "entries=" entries:long " "
                                   * "delivered_log=" deliveredLog:string " "
                                   * "delivered_email=" deliveredEmail:string " "
                                   * "outcome=" outcome:string " "
                                   * "duration_ms=" durationMs:long
| project TimeGenerated, runId, entries, deliveredLog, deliveredEmail, outcome, durationMs
| order by TimeGenerated desc
```

`outcome=partial` is the morning one audience got the report and the other did not;
`delivered_email=disabled` is a deployment where the e-mail output is switched off, which is not a
failure and must not alert as one.

A morning with no summary row at all is the report not having run, which is a different incident from
a summary row with five zeroes. That distinction is the whole reason FR-012 exists, and it is why the
alert the operability story will write fires on the **absence** of the summary as well as on the
counts in it.

The gauges are on the actuator's Prometheus endpoint rather than in the log:

```bash
curl -s localhost:8082/actuator/prometheus | grep yotresultsdistribution_oldest_non_terminal_request_age
curl -s localhost:8082/actuator/prometheus | grep yotresultsdistribution_non_terminal_requests_over_threshold
curl -s localhost:8082/actuator/prometheus | grep yotresultsdistribution_request_duration
curl -s localhost:8082/actuator/prometheus | grep yotresultsdistribution_exception
```

Both gauges read `0` on a pod that has never seen a message, and that is deliberate: a gauge that
appears only after the first incident is not something an alert can be written against. They are
**per pod**: nothing locks the sweep, so every replica publishes its own reading and an alert
aggregates them with `max()` - the oldest unfinished request is the oldest any pod can see.

## Tests

```bash
./gradlew test --tests '*SchemaMigrationV4IT' '*ProcessedRequestReportReadsIT' \
                       '*RegisterNotificationReportReadsIT' '*RegisterBatchReportReadsIT' \
                       '*RegisterStoreReportReadsIT'
./gradlew test --tests '*ExceptionReportServiceTest' '*ExceptionReportDeliveryTest' \
                       '*ExceptionReportModelTest' '*LastScheduledRunTest' \
                       '*LogEventReportSinkTest' '*EmailReportSinkTest' \
                       '*NotificationNotifyReportMailerTest'
./gradlew test --tests '*FileServicePayloadStoreIT' '*EmailReportSinkStoreIT' \
                       '*ExceptionReportEndToEndIT' '*CliDispatchIT'
./gradlew test --tests '*TelemetryPrivacyTest' '*TelemetryPrivacyIT' '*LogStatementSweepTest'
./gradlew build            # everything, including PMD/Checkstyle/JaCoCo gates
./scripts/container-smoke.sh
```
