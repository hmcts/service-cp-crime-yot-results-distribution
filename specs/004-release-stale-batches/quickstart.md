# Quickstart: Release stale in-flight batches before batching

The walkthrough that proves the increment: a batch whose render outcome never arrives is failed and
released by the next run, its registers are batched again that same run, the court centre gets its
document, and the original batch's late `document-available` moves nothing.

Everything here runs against the local stack that `specs/002-consolidate-progression-leg/quickstart.md`
sets up. Only the differences are written out.

## Local dependencies

**Start from a clean store.** `V7__retire_reconciler_vocabulary.sql` narrows three CHECK
constraints, and Postgres refuses to add a constraint to a table that already holds a violating row
— so a volume carrying a batch failed `GENERATION_TIMED_OUT` or completed by `RECONCILER` (anything
a pre-004 local run produced) makes the migration fail at start-up. `V6`, which only widens, refuses
on nothing and needs none of this. Either delete those rows or start clean:

```bash
docker compose down -v
docker compose up -d postgres servicebus-emulator artemis fileservice-postgres wiremock
```

**Host port 5433 may already be taken.** `fileservice-postgres` publishes on it, and so does the
CPP dev environment's own `postgres-ccm` container — a laptop with that stack up answers
`Bind for 0.0.0.0:5433 failed: port is already allocated`. Publish it somewhere else rather than
stopping the other stack; nothing in this walkthrough reaches the file service from the host, since
the app talks to `fileservice-postgres:5432` inside the compose network:

```yaml
# a compose override file, passed with -f alongside docker-compose.yml
services:
  fileservice-postgres:
    ports: !override
      - "15433:5432"
```

`!override` is load-bearing: Compose **appends** to a `ports` list by default, so an override
without it publishes on both and collides anyway.

**Confirmed on this compose file's own volume**, not reasoned about. On a
`service-cp-crime-yot-results-distribution_postgres-data` carrying one batch failed `GENERATION_TIMED_OUT` and
completed by `RECONCILER`, `V7` stops on

```
ERROR:  check constraint "register_batch_failure_reason_chk" of relation "register_batch"
        is violated by some row
```

and the row is still there afterwards — the migration refuses rather than dropping what it cannot
admit, which is the behaviour to want: an operator learns from a pod that will not start, not from
an absence. **That text is in Postgres's log, not the pod's.** The service names a caught exception
by class and never carries a library's message (design_rules.md, "never attach a throwable this
service did not write"), so the pod repeats
`Intake could not be started; the next probe will try again. type=…FlywayMigrateException` every
ten seconds and answers `{"status":"DOWN"}` on `/actuator/health/readiness`; the constraint's name
comes from `docker compose logs postgres`. After `docker compose down -v` and a fresh `up`, `V1`–`V7` apply in order and the store
ends with `failure_reason` admitting the six and `completed_by` admitting `'EVENT'` alone.

Two details worth knowing when this happens to you:

- **Flyway rolls the whole migration back**, so a refused `V7` leaves the schema exactly at `V6` and
  the pod simply keeps failing to start. Applying the file by hand through `psql` without a
  transaction does not: statement 1's `DROP CONSTRAINT` commits before the `ADD` fails, and the
  table is left with no failure-reason constraint at all until you re-run it. If you are poking at a
  local database by hand, wrap it in `BEGIN`/`COMMIT`.
- **`down -v` takes the file service's volume with it** (`fileservice-data`), which is what you
  want here: the payloads and the exception-report CSVs it holds belong to the batches you are
  clearing.

**Note the omission**: `sdg-echo` is *not* started. It is the helper that publishes
`document-available` back onto `public.event` after each `generate-document`, and for this
walkthrough the whole point is that no outcome ever arrives.

And it cannot be started *later* to deliver the late outcome either: **`sdg-echo` treats everything
already in WireMock's journal at start-up as history** and echoes only requests it sees afterwards,
deliberately, so that a restart does not publish a second event for a batch that has already
completed. The batch step 1 makes is in the journal before the helper exists, so step 5 publishes
that one event by hand.

WireMock's mappings need one change and one deletion:

- the `generate-document` mapping still answers **202**, as it always did;
- the **`GET document/{id}` mapping is deleted**. Nothing asks for it any more, and leaving it there
  would let a regression pass unnoticed. `docker/wiremock/README.md` loses its line about it.

## Run the service with generation enabled

The 002 command line, with two changes:

```diff
- YOTRESULTSDISTRIBUTION_GENERATION_GRACE_PERIOD=10m
+ YOTRESULTSDISTRIBUTION_GENERATION_STALE_AFTER=30m
```

and no `YOTRESULTSDISTRIBUTION_GENERATION_COMPLETION` of any kind: the setting is gone, and a run with the
generation half enabled subscribes to `public.event` unconditionally.

## 1. Make a batch that will never hear anything

**Record two registers — by hand, and this is not a shortcut.** The compose stack runs
`YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB`, and the stub payload source fetches nothing: a command published
to `yotresultsdistribution.requests` completes `no-defendants` and writes no register at all. 002's
quickstart says so in as many words. `LIVE` is the only mode that yields one and it needs the
results payload cache, reference data and a CJSCPPUID identity, none of which this stack has — so
the rows are seeded straight into the store, which is what every generation suite does too
(`support/GeneratedRegisters` records through the `RegisterStore` port for the same reason).

Two `processed_request` rows and two `processed_output` rows, one court centre, `status='RECORDED'`,
`superseded_at` and `batch_id` NULL, `register_date` today, and a `document` that is a minimal
`CourtRegisterDocument` (a venue, one recipient, one youth defendant — `GeneratedRegisters.document`
is the shape). `processed_output_recorded_shape_chk` requires `document`, `hearing_id`,
`hearing_date` and `register_time` on a RECORDED row, so all four have to be there.

```sql
SELECT count(*) FROM processed_output
 WHERE status = 'RECORDED' AND superseded_at IS NULL AND batch_id IS NULL;
-- 2
```

Then ask for a generation by hand, out of the built image:

```bash
docker compose up -d app          # wait for {"status":"UP"} on /actuator/health/readiness
docker compose exec app ./startup.sh generate-register --date 2026-09-21 --ignore-flag
```

**`java -jar build/libs/*.jar generate-register` does not work** and never did: a Boot 4 fat jar's
manifest names `JarLauncher`, which wins over anything on the command line, which is the whole
reason `docker/startup.sh` runs `CliMain` through `PropertiesLauncher` and says so in a comment
there. `./startup.sh <command>` is also the deployed form (`kubectl exec … -- ./startup.sh …`), so
this is the thing an operator actually runs.

The batch reaches `GENERATING` — WireMock answered 202 — and stops there, because nothing is going
to publish its outcome:

```text
systemdocgenerator accepted the render request for batch <batch id>, which now waits for its
    document on the public-event topic.
batch=<batch id> state=GENERATING records=2
date=2026-09-21 released=0 registers=2 batches=1 requested=1 deferred=0
```

```sql
SELECT batch_id, status, requested_at, failure_reason FROM register_batch ORDER BY assembled_at DESC LIMIT 1;
-- GENERATING, requested_at = now, failure_reason NULL
```

Its registers are stamped. **The table is `processed_output`** — V2 widened it into the register
store rather than adding a `register_record` table, and there is no such table:

```sql
SELECT count(*) FROM processed_output WHERE batch_id = '<batch id>';
-- 2
```

## 2. Confirm nothing chases it

Wait fifteen minutes — longer than the ten-minute cadence the retired timer ran at — and read
WireMock's request journal:

```bash
curl -s localhost:8089/__admin/requests | jq '[.requests[].request.url] | unique'
```

The only URLs are the `generate-document` command and the flag read. **There is no
`document/{id}`**, on any schedule, ever. That is SC-004, and it is the whole of the removal seen
from outside.

The three readings are still moving, though, which is FR-011:

```bash
for m in yotresultsdistribution_oldest_generating_age \
         yotresultsdistribution_oldest_pending_age \
         yotresultsdistribution_oldest_generated_age; do
  curl -s "localhost:8082/actuator/metrics/$m" | jq -c '.measurements'
done
# the first is a value in seconds, climbing; the other two are nought while nothing is in those
# states. Refreshed every yotresultsdistribution.generation.batch-age-refresh (10m) and NOT on read, so a
# reading taken just after a refresh lags the batch's real age by up to that interval.
```

## 3. Age the batch past the cutoff — **seventy-one minutes, not thirty-one**

Step 1 made this batch with the **operations command**, so its row carries
`system_generated = false`, and `StaleBatchReleaser` judges a batch a person asked for by the
**longer of `stale-after` (30m) and `lock-at-most-for` (70m)** — FR-017, because a manual
generation holds no run lock and has the whole requesting deadline to work in. Thirty-one minutes
releases nothing, and a run at that age is a correct run that looks like a broken feature.

```sql
UPDATE register_batch SET requested_at = requested_at - interval '71 minutes'
 WHERE batch_id = '<batch id>';
```

Thirty-one minutes is the right number for a batch the **schedule** made, which is the ordinary
case: that batch carries `system_generated = true` and is judged by `stale-after` alone. To walk
that arm instead, either let the 18:00 schedule assemble the batch in step 1 rather than the
command, or stamp the row the way the suites do:

```sql
UPDATE register_batch SET system_generated = true WHERE batch_id = '<batch id>';
-- then 31 minutes is enough
```

## 4. Run the night

```bash
docker compose exec app ./startup.sh generate-register --date 2026-09-21 --ignore-flag
```

is **not** what to run here — the on-demand command does not run the release pass, by design
(spec Assumptions). Trigger the scheduled run instead, either by waiting for 18:00 London or by
starting the service with the cron brought forward:

```bash
YOTRESULTSDISTRIBUTION_GENERATION_CRON='0 */2 * * * *' ...   # every two minutes, local only
```

What the run line says:

```text
event=register_generation_run run_id=... gate=proceed reason=flag-on batches=1 requested=1
generating=1 failed=0 pending=0 deferred=0 rows=2 rows_generating=2 ...
released_batches=1 released_registers=2 contended=0 duration_ms=...
```

(`reason=flag-on` on this stack: the committed WireMock mapping answers the flag ON, so the
schedule proceeds because the lever says so. `reason=overridden` is what `--ignore-flag` produces,
and the scheduled run takes no such argument.)

`released_batches=1 released_registers=2` — where the line used to carry `reconciled=`. The two
registers are counted here **and** in `rows=`, because the same run re-batched them: the released
numbers say what the night had to undo, and are deliberately not part of either total. And in the
store:

```sql
SELECT batch_id, status, failure_reason, completed_by FROM register_batch ORDER BY assembled_at;
-- <old batch>  FAILED      NOT_COMPLETED_BY_NEXT_RUN   NULL
-- <new batch>  GENERATING  NULL                        NULL

SELECT batch_id, count(*) FROM processed_output GROUP BY batch_id;
-- the two registers are on the NEW batch
```

The old batch keeps its row, carrying what happened to it; its registers moved. The court centre is
getting its document tonight.

## 5. Deliver the late outcome, and watch nothing happen

Publish, **by hand**, a `document-available` naming the **old** batch's correlation and payload id.
Starting `sdg-echo` now will not do it: it ignores every `generate-document` already in the journal
when it comes up (see *Note the omission* above), and the old batch's request is one of them — it
would echo only the **new** batch's, which is a different event about a different batch.

The two ids come from the request the service actually sent:

```bash
curl -s 'localhost:8089/__admin/requests?limit=50' \
  | jq -r '.requests[] | select(.request.url|contains("generate-document")) | .request.body' \
  | jq -c '{sourceCorrelationId, payloadFileServiceId, originatingSource}'
```

and the publisher is a dozen lines: copy `publish()` and `document_available_from()` out of
`docker/sdg-echo/sdg-echo.py`, substitute the old batch's two ids, and run it on the compose network
so `artemis` resolves — the frame and the envelope must be that file's, because the listener needs
the `CPPNAME` header, the `_metadata.name` and `originatingSource = YotResultsDistributionService` before it
will look at the message at all.

```bash
docker run --rm --network service-cp-crime-yot-results-distribution_default \
  -v "$PWD/publish-document-available.py:/p.py:ro" python:3.13-alpine \
  python /p.py '<old batch payload file id>' '<old batch id>' YotResultsDistributionService
```

The listener acknowledges it and drops it:

```bash
curl -s 'localhost:8082/actuator/metrics/yotresultsdistribution_public_events_ignored_total?tag=reason:terminal-batch' \
  | jq -c '.measurements'
# [{"statistic":"COUNT","value":1.0}] - "terminal-batch" is the reason this increment adds,
# because before it this drop was a WARN and moved no counter at all
```

and the store is unchanged:

```sql
SELECT status, failure_reason FROM register_batch WHERE batch_id = '<old batch id>';
-- still FAILED, still NOT_COMPLETED_BY_NEXT_RUN
SELECT count(*) FROM register_notification WHERE batch_id = '<old batch id>';
-- 0
```

That is SC-003: the Youth Offending Team is told once, by the batch that actually rendered.

## 6. The other side of the boundary

Repeat from step 1 but age the batch by **ten** minutes instead of seventy-one, and run the night.
The batch is untouched and its registers are still stamped.

For `deferred=1` there has to be something to defer, so **record one more register for the same
court centre and register date** while the batch is in flight — with nothing unbatched, the run
finds nothing to assemble and says `deferred=0`, which is right and proves nothing. With one:

```text
event=register_generation_run run_id=... gate=proceed reason=flag-on batches=0 requested=0
generating=0 failed=0 pending=0 deferred=1 rows=2 rows_deferred=2 ...
released_batches=0 released_registers=0 contended=0 duration_ms=...
```

the assembler passed its court centre day over, exactly as it does today (US2.1, US2.2). The
line's fields are `released_batches` and `released_registers`; there is no bare `released=`.

## Startup refusals to try

Against `./gradlew bootRun`, or against the built image with
`docker compose run --rm -e <VAR>=<value> app`, which is what proves the deployed artefact:

```bash
YOTRESULTSDISTRIBUTION_GENERATION_STALE_AFTER=0s
# refuses at PropertiesValidator, exit 1:
#   yotresultsdistribution.generation.stale-after (PT0S) must be positive — a timeout that never expires
#   is a run that never ends

YOTRESULTSDISTRIBUTION_GENERATION_BATCH_AGE_REFRESH=-1m
# refuses the same way, naming yotresultsdistribution.generation.batch-age-refresh (PT-1M)

YOTRESULTSDISTRIBUTION_GENERATION_COMPLETION=poll-only
# starts ("Started Application in ... seconds"), and the string `completion` appears nowhere in
# the start-up log: the key is gone. A deployment that still sets it is setting nothing, which is
# why the STE values are on the outside-this-repo list.
```

## Reading it in a deployed environment

```kql
// batches a night gave up on, and the hearings that came back with them
ContainerLogV2
| where LogMessage has "event=register_generation_run"
| extend batches   = extract(@"released_batches=(\d+)", 1, LogMessage, typeof(int)),
         registers = extract(@"released_registers=(\d+)", 1, LogMessage, typeof(int))
| summarize sum(batches), sum(registers) by bin(TimeGenerated, 1d)
```

And the other side of it, which used to be invisible:

```kql
// outcomes arriving for batches that had already ended
ContainerLogV2
| where LogMessage has "yotresultsdistribution_public_events_ignored_total"
| where LogMessage has "terminal-batch"
```

A night with a non-zero `released` is a night something did not hear from systemdocgenerator. A run
of them is a broker or a renderer to investigate — which is what the retired `reconciled` counter
used to say, said by the mechanism that replaced it.
