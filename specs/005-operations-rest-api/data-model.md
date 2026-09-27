# Data model: the operations API

Seven endpoints, their requests, their success shapes and every refusal each can answer. This is
what `src/main/resources/openapi.yaml` must describe and what the contract test asserts against; the
OpenAPI document is the machine-readable form of this page, and where the two disagree the document
is the contract and this page is the error.

**Everything below is bounded codes, counts and identifiers.** No response carries defendant detail,
an unmasked address, a payload, a register document, an exception message or a value the caller
supplied.

All responses are `application/json`. Every non-2xx is an RFC 9457 `ProblemDetail` with a `reason`
extension carrying a bounded code, except `401` and `403`, which the authorisation filter produces
through `sendError` and which therefore come back through `api/OperationsErrorAttributes` (research
R5) in the same shape but without passing the advice.

---

## Common

### `ProblemDetail`

| Field | Type | Notes |
|---|---|---|
| `status` | int | |
| `title` | string | A fixed phrase per status, from a closed set |
| `reason` | string | **The** bounded code. What a runbook greps for |
| `argument` | string? | On a `400` over one argument: this service's own name for it (`date`, `batchId`, `recordedBefore`, `sharedBefore`, `since`, `ignoreFlag`), never the value |
| `batchId` / `runId` / `date` | string? | Identifiers, where the refusal is about one |
| `released` / `registers` / `batches` / `requested` / `deferred` | int? | Only on a `500` that follows partly-done work: the partial tally, because "the day stands as whatever this run had already written down" |
| `accepted` / `failed` / `state` | int?, int?, string? | The notify tally, on the two dispositions that tried and could not finish (`claim-lost`, `incomplete`) and on `already-notifying` — the same reason the partial tally above exists |

`detail` is **never** populated from an exception message, a store's words or a far end's words.

### The reason codes

Carried over from the commands: `flag-off`, `flag-unreadable`, `overridden`, `missing-argument`,
`unreadable-argument`, `settled`, `already-notifying`, `claim-lost`, `incomplete`,
`generation-failed`, `resend-failed`, `listing-failed`, `supersession-failed`, `report-not-built`,
`email-output-disabled`, `email-output-not-wired`, `key-in-flight`, `outside-the-bound`,
`command-not-wired`.

New in this increment, one per new rule: `SCHEDULE_RUNNING` (the background run could not take the
register-generation lock), `FLAG_ON` (supersede, refused because this service is live),
`OVERRIDE_REQUIRES_BATCH` (`ignoreFlag` without a `batchId`), `SUPERSEDE_INSTANT_IN_FUTURE`,
`SUPERSEDE_INSTANT_TOO_OLD`, `UNKNOWN_BATCH`, `STORE_UNAVAILABLE`, `DOWNSTREAM_REFUSED`,
`DOWNSTREAM_UNAVAILABLE`.

Added at the increment's gate, and about the surface rather than about an action:
`UNSUPPORTED_CONTENT_TYPE` (`415`) — a body declared in a format no endpoint here takes.
`cp-audit-filter-springboot` 1.0.5 hands a request whose `Content-Type` begins `multipart/` down
the chain and publishes neither of its two events, so a call declaring one would be served
unaudited; `api/OperationsContentTypeFilter` refuses it between the two estate filters (order
`+40`) — outside the audit filter, because once it has decided to skip there is nothing left to
refuse, and inside the authorisation filter, so an unauthenticated caller is answered `401` first.
`UNEXPECTED` (`500`) — a failure nobody classified. `AuditFilter.doFilterInternal` has no
`try`/`finally` around the chain, so an exception that leaves the dispatcher leaves a request event
with no response event beside it; the advice's one fallback answers it under this code so the
outcome is on the wire and on the event. A `500` this service can explain is still a 409, a 503 or
a 502 it failed to classify — this code is what is left when it could not.

The OpenAPI document carries **both** conventions, deliberately, and normalises neither: the codes
the six commands already printed keep their own hyphenated characters, so a runbook step that greps
one keeps working, and the rules increment 005 adds are named in this document's own
`SCREAMING_SNAKE` convention. `api/OpenApiContractTest` asserts the enum against
`domain/OperationsReason` member for member, so the spelling of a code is fixed in one place and
cannot drift in the other.

### Applies to every endpoint

| Condition | Status | Reason |
|---|---|---|
| No `CJSCPPUID` | `401` | produced by the filter |
| Caller not in "Second Line Support"; identity service unreachable; no rule for the action | `403` | produced by the filter |
| Body will not parse, or carries an unknown field | `400` | `unreadable-argument` |
| Unmapped path or method | framework default, through `OperationsErrorAttributes` | — |
| `Content-Type` beginning `multipart/` | `415` | `UNSUPPORTED_CONTENT_TYPE`, refused by `OperationsContentTypeFilter` after the authorisation filter and before the audit one |
| A failure nobody classified | `500` | `UNEXPECTED`, from the advice's one fallback |

---

## 1. `GET /operations/flag`

Action `yot-results-distribution-operations.check-flag`. Replaces `check-flag`. Reads the flag — that is its
purpose. Takes nothing.

**200**

| Field | Type | Values |
|---|---|---|
| `flag` | string | `ON` \| `OFF` \| `UNREADABLE` |
| `reason` | string? | present only with `UNREADABLE`: the reading's own bounded code |

All three readings are `200`: the endpoint answered its question (spec assumption 2). Refusals:
`401`, `403` only.

---

## 2. `GET /operations/batches?date=D`

Action `yot-results-distribution-operations.list-batches`. Replaces `list-batches --date D`. `date` is a
required ISO local date.

**200**

```
{ "date": "2026-09-04",
  "batches": [
    { "batchId": "…", "courtHouse": null, "state": "NOTIFIED", "records": 12,
      "recipients": [ { "address": "j***@yot.example.gov.uk", "outcome": "ACCEPTED" } ] } ] }
```

`courtHouse` is `null` where the batch has none (the command printed `-`). `address` is masked by
the **same rule** `ListBatchesCli.masked` used: the first character of a local part longer than one,
then `***`, then everything from the last `@`. The unmasked address appears nowhere — not in the
response, not in a log line, not in the audit event.

| Refusal | Status | Reason |
|---|---|---|
| `date` absent | `400` | `missing-argument` |
| `date` not a date | `400` | `unreadable-argument`, `argument: date` |
| The store will not answer | `503` | `listing-failed` |
| Generation half off on this pod | `501` | `command-not-wired` |

---

## 3. `GET /operations/registers/recorded-while-off`

Action `yot-results-distribution-operations.list-recorded-while-off`. Replaces
`list-batches --recorded-while-off`. Takes nothing.

**200**

```
{ "records": [ { "recordId": "…", "hearingId": "…", "registerDate": "2026-09-04",
                 "flag": "OFF" } ] }
```

| Refusal | Status | Reason |
|---|---|---|
| The store will not answer | `503` | `listing-failed` |

---

## 4. `POST /operations/batches/generate`

Action `yot-results-distribution-operations.generate-register`. Replaces `generate-register`. **Asynchronous**
(research R16).

**Request**

| Field | Type | Required | Notes |
|---|---|---|---|
| `date` | ISO local date | yes | The register date |
| `courtHouse` | string | no | One court house of that date |
| `batchId` | UUID | no | One batch of that date |
| `recordedBefore` | ISO instant | no | Only registers recorded before this |
| `ignoreFlag` | boolean | no, default `false` | The break-glass. **Only with `batchId`** |

**202**

```
{ "runId": "…", "date": "2026-09-04", "overridden": false }
```

The work runs in the background, on the generation scheduler's single-threaded executor, holding the
register-generation ShedLock. What it did is read back from the `RunReport` line (`run_id`,
`trigger=operator`, `reason=overridden` where the flag was overridden, and the same tally the
command printed) and from endpoint 2.

| Refusal | Status | Reason |
|---|---|---|
| `date` absent | `400` | `missing-argument` |
| `date`, `batchId` or `recordedBefore` will not read | `400` | `unreadable-argument` + `argument` |
| `ignoreFlag: true` with no `batchId` | `400` | `OVERRIDE_REQUIRES_BATCH` |
| Flag OFF and no override | `409` | `flag-off` |
| Flag unreadable and no override | `409` | `flag-unreadable` |
| Generation half off on this pod | `501` | `command-not-wired` |

**Recorded as the background run's outcome, not as a status**: `SCHEDULE_RUNNING` (the lock could
not be taken), `generation-failed`, and per batch `key-in-flight` / `outside-the-bound` for a
withheld one. A caller learns them from the run report and endpoint 2 — which is the price of the
`202` and is stated so nobody looks for them in the response.

---

## 5. `POST /operations/batches/{batchId}/notify`

Action `yot-results-distribution-operations.notify-register`. Replaces `notify-register --batch B`.
Synchronous. No body. `{batchId}` is the one path parameter in the whole document, which is what the
audit filter resolves path parameters for.

**200** — disposition `SETTLED`

```
{ "batchId": "…", "accepted": 3, "failed": 0, "state": "NOTIFIED", "disposition": "settled" }
```

| Refusal | Status | Reason |
|---|---|---|
| `batchId` not a UUID | `400` | `unreadable-argument`, `argument: batchId` |
| No such batch | `404` | `UNKNOWN_BATCH` |
| Another notifier holds the claim | `409` | `already-notifying` |
| The claim was lost part-way | `500` | `claim-lost` |
| Some recipients left unsettled | `500` | `incomplete` |
| notificationnotify refused / did not answer | `502` / `504` | `DOWNSTREAM_REFUSED` / `DOWNSTREAM_UNAVAILABLE` |
| The store will not answer | `503` | `STORE_UNAVAILABLE` |
| Generation half off on this pod | `501` | `command-not-wired` |

`ALREADY_NOTIFYING` is a `409` because this call changed nothing; `CLAIM_LOST` and `INCOMPLETE` are
`500` because it tried and got part-way (spec assumption 3). The claim that decides between them is
`RegisterNotifierService`'s own, unchanged.

The `404` is answered for `NoSuchBatchException` and for nothing else. The notifier raises an
`IllegalStateException` for two further endings — a batch that exists and carries no document
because it was never generated, and a notification row the store refused and then holds none for —
and both of those keep the command's `500 resend-failed`: the identifier the operator gave is
right, and sending them back to check it is the one thing a bounded code must not do.

---

## 6. `POST /operations/registers/supersede`

Action `yot-results-distribution-operations.supersede-before`. Replaces `supersede-before --shared-before T`.
Synchronous, and **stricter than its command** (spec assumption 11).

**Request**

| Field | Type | Required | Notes |
|---|---|---|---|
| `sharedBefore` | ISO instant | yes | Never defaulted |
| `dryRun` | boolean | no, default `false` | Answers the count and supersedes nothing |

**200**

```
{ "superseded": 47, "sharedBefore": "2026-09-04T17:00:00Z", "dryRun": false }
```

The count goes into the audit event as well as the response.

| Refusal | Status | Reason |
|---|---|---|
| `sharedBefore` absent | `400` | `missing-argument` |
| `sharedBefore` will not read | `400` | `unreadable-argument`, `argument: sharedBefore` |
| `sharedBefore` in the future | `400` | `SUPERSEDE_INSTANT_IN_FUTURE` |
| `sharedBefore` older than `yotresultsdistribution.operations.supersede-max-age` | `400` | `SUPERSEDE_INSTANT_TOO_OLD` |
| Flag ON — this service is live | `409` | `FLAG_ON` |
| Flag unreadable | `409` | `flag-unreadable` (fail-closed) |
| The store will not answer | `503` | `supersession-failed` |

There is **no** `ignoreFlag` here and there never will be: the endpoint exists for a rollback, and a
rollback while this service is the live implementation is not a rollback.

---

## 7. `POST /operations/exception-reports`

Action `yot-results-distribution-operations.report-exceptions`. Replaces `report-exceptions`. Synchronous.
**Reads the cutover flag nowhere**, as its command did not.

**Request**

| Field | Type | Required | Notes |
|---|---|---|---|
| `since` | string | no | An ISO instant, an ISO-8601 duration, or `<n>d` / `<n>h` / `<n>m` / `<n>s`. Absent means "since the previous scheduled report", computed from the report cron exactly as the command computed it |
| `email` | boolean | no, default `false` | Also deliver through the e-mail sink |

**200**

```
{ "runId": "…", "window": { "from": "…", "to": "…" },
  "entries": [ { "kind": "…", "source": "…", "requestId": "…", "hearingId": "…",
                 "hearingDay": "…", "batchId": "…", "notificationId": "…",
                 "courtCentreId": "…", "registerDate": "…", "status": "…",
                 "attempts": 2, "reason": "…", "ageSeconds": 5400 } ],
  "counts": { "<kind>": 0 }, "truncated": 0,
  "delivered": { "log": "…", "email": "…" },
  "outcome": "…", "durationMs": 128 }
```

Every entry field is an identifier, a bounded code or a count — the same set the command printed,
with the absent ones omitted rather than rendered. An empty window answers with `entries: []` and
the counts, never with silence.

| Refusal | Status | Reason |
|---|---|---|
| `since` will not read | `400` | `unreadable-argument`, `argument: since` |
| `email` asked, output switched off | `409` | `email-output-disabled` |
| `email` asked, output on but no sink | `409` | `email-output-not-wired` |
| The report could not be built | `500` | `report-not-built` |
| A sink did not take it | `500` | `report-not-delivered`, with `delivered` in the properties so the operator can see which |

---

## What the audit event carries

Not a request or response body (`audit.http.include-payload-body: false`). Through the starter's
own seam, per call:

| Field | Source |
|---|---|
| `_metadata.context.user` | the `CJSCPPUID` — the one place the caller is named on purpose |
| `action` | the server-derived action name |
| `outcome` | the status family and the bounded reason |
| `flagOverride` | `true` / `false`, on the regeneration endpoint |
| `runId` | on the regeneration endpoint |
| `superseded` | the count, on the supersede endpoint |
| path and query parameters | merged by the library regardless of the body switch: a date and a batch id, both bounded identifiers |
