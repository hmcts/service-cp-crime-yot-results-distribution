# Quickstart: the operations API

This replaces the `kubectl exec ... -- ./startup.sh <command>` walkthrough in
`specs/002-consolidate-progression-leg/quickstart.md`. The commands are gone; the seven actions are
endpoints.

## Before anything

```bash
kubectl config current-context       # ALWAYS. Confirm the environment before you touch it.
```

Every call needs a `CJSCPPUID` identity that resolves, in usersgroups, to a member of **"Second Line
Support"**. In a deployed environment the gateway strips whatever you send and injects the
authenticated identity, so the header below is what the *gateway* puts there, not what you choose:
you authenticate to the gateway and it does the rest. Locally, where there is no gateway, you send
it yourself.

```bash
BASE=https://<internal-host>/yotresultsdistribution      # the internal route; never exposed outside the estate
H='-H Content-Type:application/json'
```

Nothing in this document takes an argument that is not a date, an instant, an identifier or a
boolean, and nothing it prints is anything but a bounded code, a count, an identifier or a masked
address.

## Read the cutover flag

```bash
curl -s "$BASE/operations/flag"
# {"flag":"ON"}
# {"flag":"OFF"}
# {"flag":"UNREADABLE","reason":"flag-unreadable"}     <- still 200: the endpoint answered
```

All three are `200`. `UNREADABLE` is a reading, not an outage of this service.

## List a register date's batches

```bash
curl -s "$BASE/operations/batches?date=2026-09-04"
```

```json
{ "date": "2026-09-04",
  "batches": [
    { "batchId": "2f1c…", "courtHouse": "Leeds Youth Court", "state": "NOTIFIED",
      "records": 12,
      "recipients": [ { "address": "j***@yot.example.gov.uk", "outcome": "ACCEPTED" } ] } ] }
```

Recipient addresses are masked, exactly as the command masked them.

## See what was recorded while the flag was off

```bash
curl -s "$BASE/operations/registers/recorded-while-off"
```

## Regenerate a register date

```bash
curl -s -X POST $H "$BASE/operations/batches/generate" -d '{
  "date": "2026-09-04"
}'
# 202  {"runId":"1b9e…","date":"2026-09-04","overridden":false}
```

**It answers `202` and keeps working.** The renders are requested in the background, under the same
lock the 18:00 run takes. Watch it with the run report line (`run_id=1b9e… trigger=operator`) and
with the batches listing above.

Narrowed to one court house, or to registers recorded before an instant:

```bash
curl -s -X POST $H "$BASE/operations/batches/generate" -d '{
  "date": "2026-09-04",
  "courtHouse": "Leeds Youth Court",
  "recordedBefore": "2026-09-04T17:00:00Z"
}'
```

With the flag off, it refuses and changes nothing:

```bash
# 409  {"status":409,"reason":"flag-off"}
```

The break-glass — **one batch at a time, and only with a batch id**:

```bash
curl -s -X POST $H "$BASE/operations/batches/generate" -d '{
  "date": "2026-09-04",
  "batchId": "2f1c…",
  "ignoreFlag": true
}'
# 202, and the override is in the audit event with your identity
#      and on the run line as reason=overridden
```

Without a batch id it is refused, deliberately:

```bash
curl -s -X POST $H "$BASE/operations/batches/generate" -d '{"date":"2026-09-04","ignoreFlag":true}'
# 400  {"status":400,"reason":"OVERRIDE_REQUIRES_BATCH"}
```

## Re-request a batch's owed recipients

```bash
curl -s -X POST "$BASE/operations/batches/2f1c…/notify"
# 200  {"batchId":"2f1c…","accepted":3,"failed":0,"state":"NOTIFIED","disposition":"settled"}
```

Only the recipients no e-mail has been accepted for are asked again. If another notifier holds the
batch's claim you get `409 already-notifying` and nothing happened; `500 claim-lost` or
`500 incomplete` mean this call tried and got part-way, and the batch stands where it is.

## Supersede what was recorded before an instant

**Only while the flag says OFF**, and always look before you leap:

```bash
curl -s -X POST $H "$BASE/operations/registers/supersede" -d '{
  "sharedBefore": "2026-09-04T17:00:00Z",
  "dryRun": true
}'
# 200  {"superseded":47,"sharedBefore":"2026-09-04T17:00:00Z","dryRun":true}   <- nothing changed
```

Then, if 47 is the number you expected:

```bash
curl -s -X POST $H "$BASE/operations/registers/supersede" -d '{
  "sharedBefore": "2026-09-04T17:00:00Z"
}'
# 200  {"superseded":47,"sharedBefore":"2026-09-04T17:00:00Z","dryRun":false}
```

Refused with `409 FLAG_ON` while this service is live, `409 flag-unreadable` when the flag cannot be
read, `400` for an instant in the future or older than the configured bound. There is no override.

## Pull the exception report

```bash
curl -s -X POST $H "$BASE/operations/exception-reports" -d '{"since":"6h"}'
```

`since` takes an ISO instant, an ISO-8601 duration, or `6h` / `2d` / `30m` / `90s`. Leave it out and
the window runs from the previous scheduled report. Add `"email": true` to send it as well as read
it — `409 email-output-disabled` if that output is switched off in this environment.

## Local

`docker compose up` as before, then:

```bash
curl -s localhost:8082/operations/flag
```

The compose environment switches `authz.http.enabled` and `audit.http.enabled` off, with the reason
beside each, so no identity header is needed; `cp.audit.enabled` — the audit transport — is already
off, because `application.yaml` ships it `${CP_AUDIT_ENABLED:false}` so a machine with no audit
broker starts. Nothing is published to an audit broker either way.

The two filters default to **secure** (`true` in `application.yaml`, against library defaults of
off) and the transport does not, which is the one asymmetry worth knowing: the HTTP audit filter is
built only inside the auto-configuration class the transport's key gates, so a deployed environment
carries Principle III's condition (b) by setting `CP_AUDIT_ENABLED=true` in its values with the
broker's connection from Key Vault. A pod with the filter on over a transport that is off starts,
serves, and says so once at WARN. None of this is a refusal: what keeps a deployed pod authorised
and audited is the values file and the deployment review.

## What is gone

`./startup.sh generate-register|notify-register|list-batches|supersede-before|check-flag|report-exceptions`
no longer exist, and neither does the `yotresultsdistribution.cli` property or the entrypoint's command
dispatch. The image starts the application, full stop. If `kubectl exec` is the only way you can
reach a pod, the operations API is not deployed yet — see the deployment gates in `spec.md`.
