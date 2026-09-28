# service-cp-crime-yot-results-distribution

When a hearing is resulted on the Common Platform, the court-register flow assembles one register
document per hearing covering **youth defendants only**, matches recipients (Youth Offending Teams)
against NOW-subscription rules keyed on the court centre, batches the documents per (court centre,
register date), renders a PDF at 18:00 each weekday and e-mails it to the matched teams. Today that
flow is split across a Node.js Azure Durable Functions app (assembly and matching) and the
progression context (batching, the nightly PDF through systemdocgenerator, the e-mail through
notificationnotify), and it fails silently at several points on both halves.

This service replaces **both halves** with one Spring Boot pipeline on AKS. It consumes hearing
commands from the Azure Service Bus queue `yotresultsdistribution.requests`, builds the register from the
Redis claim-check payload (with the results-query fallback), matches subscriptions, validates the
document against the frozen register contract and **records** it in its own store; a service-owned
job at **18:00 Europe/London, Monday to Friday** batches the recorded rows, writes the PDF payload
into the platform file service, asks systemdocgenerator to render the unchanged `OEE_Layout5`
template, learns the outcome from systemdocgenerator's public events, and sends one
notificationnotify e-mail per Youth Offending Team with the PDF attached. Every command and every
batch has a recorded terminal state; nothing is swallowed.

The whole flow is switched between the legacy implementation and this service by **one Azure App
Configuration feature flag, `YotResultsDistributionService`**, read by the results producer, by the legacy
function-app triggers and by this service's nightly job. Flag on: the producer publishes, the legacy
stands down, this service generates. Flag off: the reverse, and progression's still-scheduled job
generates again. Every failure to read the flag leaves the legacy in charge.

It is deliberately **not** a bug-for-bug port. The defects catalogued in the design are **fixed**,
each with a pinning test and a sign-off state, in the [defect-fix register](doc/DEFECT-FIXES.md);
legacy behaviour remains the oracle for everything not catalogued there. Externally-owned
remediations (the legacy repo's kill-switch, the producer) are registered as pending and tracked to
conclusion before cutover.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.yotresultsdistribution`                       |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Design

The design lives on Confluence and is the authority for what this service does and why:

**[Court Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004104319/Court+Register+Service)**
(CRA space) — as-is topology and sequence, the to-be architecture, the consolidation of progression's
court-register leg, the one-flag cutover, and the open questions.

This repository carries no design narrative of its own. What it does carry:

| Artefact | Location | Purpose |
|---|---|---|
| **Defect-fix register** | [doc/DEFECT-FIXES.md](doc/DEFECT-FIXES.md) | Every catalogued legacy defect (function-app `C` rows and progression-leg `P` rows), its fix, its pinning test and its sign-off state — the quality gate the constitution enforces |
| Engineering constitution | [.specify/memory/constitution.md](.specify/memory/constitution.md) | The non-negotiable principles (fix-first, TDD, message-contract first, ports and adapters, nothing swallowed, privacy, estate conventions) |
| Specifications | [specs/](specs/) | Spec Kit increments: `001-court-register-port` (complete) and `002-consolidate-progression-leg` (in progress) — spec, plan, research, data model, tasks, checklists |
| Inbound contract | [src/main/resources/contracts/distribution-command.schema.json](src/main/resources/contracts/distribution-command.schema.json) | The `yotresultsdistribution.requests` message, `additionalProperties: false` |
| Register contract | [src/main/resources/contracts/progression/](src/main/resources/contracts/progression/) | The `courtRegisterDocument/*` schemas frozen at `criminal-court-public-model` 17.103.13, with provenance — enforced at the write into the register store |
| Working conventions | [CLAUDE.md](CLAUDE.md) | Build loop, contract rule, fix-first rule, build and test commands |

## Status

- **Increment 001 — court-register-port: complete.** The intake half is implemented and green
  under the full quality gates: transport with explicit settlement and the durable idempotency guard,
  the ported transformation (fragment build, subscription matching, the twelve-mapper aggregation
  document), contract validation against the vendored schemas, and the terminal-state processed log.
  The differential audit against 381 recorded runs of the real function app found zero unattributed
  differences.
- **Increment 002 — consolidate-progression-leg: complete.** The POST to progression is replaced by
  the register store, with supersession enforced at the write; the nightly job assembles one batch
  per (court centre, register date), writes the PDF payload into the file service, asks
  systemdocgenerator for the unchanged `OEE_Layout5` render, learns the outcome from the
  `public.event` topic — with a grace-period reconciler for the outcomes that never arrive, since
  retired by 004 in favour of the next run releasing a stale batch — and
  sends one notificationnotify e-mail per matched Youth Offending Team. The flag gate, the five
  operations commands — replaced by the operations API in 005 — and the run report land with it, and
  the progression-leg `P` rows are appended to the defect-fix register. The consolidation audit reproduces the recorded progression corpus by
  manifest digest on every build, with one attributed deviation (P10). Task-level detail is the
  checkbox state in `specs/002-consolidate-progression-leg/tasks.md`.
- **Increment 003 — exception-report: complete.** Nothing silently wrong any more: a 07:00
  Europe/London weekday run, on a scheduler of its own and under its own lock, reports every FAILED
  request, every request still in flight past its threshold, every batch late at one of its three
  stages, every failed batch and every refused notification over the window that opens at the
  previous scheduled run. It reads the store and nothing else, is gated by the cutover flag nowhere,
  and runs whatever `yotresultsdistribution.generation.enabled` says. Two sinks: the Log Analytics one, which
  writes one `yotresultsdistribution_exception` event per exception and one ten-field
  `yotresultsdistribution_exception_report` summary per run, and the e-mail one, which renders the list as a
  CSV into the framework file service and asks notificationnotify to attach it — one send per
  support address. `IntakeAgeSweep` refreshes the two intake gauges on its own fixed delay in every
  JVM and under no lock, which with the request-duration timer and the report's own
  counters completes the four instruments of design section 11. `report-exceptions` was the sixth
  operations command, producing the same report on demand for a window given as an instant or a
  duration; increment 005 made it `POST /operations/exception-reports`, unchanged in what it does. Task-level detail is the checkbox state in `specs/003-exception-report/tasks.md`.
  **The e-mail output ships switched off in every environment**, and is gated on the
  notificationnotify team providing the template it is sent under: user stories 1, 2, 3 and 5 are
  complete without it, `--email` is refused with a bounded reason rather than silently doing
  nothing, and startup refuses the switch with no template, no recipients, no file-service URL or
  no notificationnotify endpoint. No `doc/DEFECT-FIXES.md` row is added or amended — a new
  capability is not a deviation from a legacy oracle.
- **Increment 004 — release-stale-batches: complete.** A batch whose render outcome never arrived
  is no longer waited on: the nightly run's **first act** fails every PENDING or GENERATING batch
  older than its cutoff under the bounded `NOT_COMPLETED_BY_NEXT_RUN` and gives its registers back,
  so the same run re-batches them and the court centre gets its document that night instead of the
  next one. One fenced statement per batch, so nothing about one batch can end a night; a batch
  every attempt at lost the day's active-register key is reported contended and reached again by
  the next run. The cutoff is `yotresultsdistribution.generation.stale-after` for the schedule's own
  batches and the longer of that and the run lock for a batch an operator asked for, which holds no
  run lock and has the whole requesting deadline to work in. The grace-period reconciler and
  systemdocgenerator's query endpoint are retired with it — there is nothing left to ask, so
  `GENERATION_TIMED_OUT` leaves the vocabulary and the store's constraints — and `BatchAgeSweep`
  takes over the three in-flight batch-age gauges the reconciler used to refresh, on its own fixed
  delay in every JVM that carries the generation half, and under no lock. An outcome that arrives for a batch this
  service had already ended moves nothing and is counted under `terminal-batch`, which is what
  stops a Youth Offending Team being e-mailed twice about one day; the 07:00 report tells a
  released batch from a failed one, reporting it as the informational `BATCH_RELEASED`. Task-level
  detail is the checkbox state in `specs/004-release-stale-batches/tasks.md`.
- **Cutover** is a separate step now that both increments are signed off: the producer's queue
  publisher and the legacy kill-switch already exist as patterns; the flag is the only lever. Two
  register rows are tracked to conclusion first — P6 and P7 depend on progression's retirement PR
  merging, which nothing in this repository can assert — alongside the legacy-repo items C18a, C28
  and C34, the producer-repo item C18b and the SIT→STE replay gate.

## Operations API

This service exposes **no business REST API**: no hearing is submitted to it over HTTP, no register
is read out of it, no batch is created by a caller. Its HTTP surface is Spring Boot Actuator and,
since increment 005, seven named operator actions under `/operations/**` — the actions that were a
CLI in the image until then, reached by `kubectl exec`. They are described in
`src/main/resources/yot-results-distribution-openapi.yaml`, which this repository owns and versions.

### Who may call, and how identity reaches the pod

**"Second Line Support", on every endpoint, and no other group.** The rules are drools, in
`src/main/resources/acl/operations-rules.drl`, one per action; an action with no rule is denied, so
an endpoint added without one is unreachable rather than open.

The caller is named by the **`CJSCPPUID`** header, and that header is the **gateway's assertion,
never a client's claim**: the gateway authenticates the caller, strips whatever `CJSCPPUID` arrived
on the wire and injects the authenticated identity. `cp-auth-rules-filter` resolves that identity's
groups against usersgroups. Every denial is default-deny — a missing or blank identity is `401`, and
a caller in another group, an identity service that cannot be asked, and an action with no rule are
all `403`.

The **action** a request is authorised against is derived by this service from the path and the
method, and overwrites whatever action header or vendor media type the caller sent. A caller
admitted to the listing cannot reach the regeneration by naming it in a header.

### What is audited, and what never leaves in a response

`cp-audit-filter-springboot` publishes **two** events per call — one before the action and one
after — carrying the caller, the derived action, the outcome, and this service's own bounded
additions: whether the cutover flag was overridden, the run id a regeneration answered with, and the
count a supersession gave up. A call whose request event cannot be published is **refused**
`503 AUDIT_UNAVAILABLE` rather than taken: an endpoint reachable unaudited is an endpoint that may
not exist.

Nothing a caller typed comes back, in a body or in a log line. A refusal names the **argument**, by
this service's own name for it, and never the value. No exception message, no store's or far end's
own words, no defendant detail, and recipient addresses masked exactly as the `list-batches` command
masked them. The `CJSCPPUID` value appears in **no** log line: the audit event is the one place the
caller is named on purpose.

### The seven endpoints

Every row's refusals are additional to the four the surface itself answers: `401` no identity,
`403` not admitted, `415 UNSUPPORTED_CONTENT_TYPE` for a `Content-Type` beginning `multipart/`, and
`503 AUDIT_UNAVAILABLE` where the call could not be audited. Every non-2xx answer is a
`ProblemDetail` carrying a bounded `reason`. `500 UNEXPECTED` is the advice's single fallback and
means a defect: a `500` this service can explain is a `409`, a `503` or a `502` it failed to
classify.

| Action | Method and path | Body | 2xx | Refusals |
|---|---|---|---|---|
| Read the cutover flag | `GET /operations/flag` | — | `200` `{flag, reason?}` — `ON`, `OFF` or `UNREADABLE`, the last with its own bounded cause | none of its own: `UNREADABLE` is a **reading**, answered `200`, not an outage |
| List a date's batches | `GET /operations/batches?date=` | — | `200` `{date, batches[]}` — per batch: id, court house, state, record count, masked recipients with outcomes | `400 missing-argument` / `unreadable-argument` (`date`); `503 listing-failed`; `501 command-not-wired` |
| Review what was recorded while the flag was off | `GET /operations/registers/recorded-while-off` | — | `200` `{records[]}` | `503 listing-failed` |
| Regenerate a register date | `POST /operations/batches/generate` | `{date, courtHouse?, batchId?, recordedBefore?, ignoreFlag?}` | **`202`** `{runId, date, overridden}` — the renders happen in the background on the generation scheduler, under the 18:00 lock | `400 missing-argument` / `unreadable-argument`; `400 OVERRIDE_REQUIRES_BATCH`; `409 flag-off`; `409 flag-unreadable`; `409 SCHEDULE_RUNNING`; `409 KEY_IN_FLIGHT`; `409 OUTSIDE_THE_BOUND`; `500 generation-failed`; `501 command-not-wired` |
| Re-request a batch's owed recipients | `POST /operations/batches/{batchId}/notify` | — | `200` `{batchId, accepted, failed, state, disposition}` | `400 unreadable-argument`; `404 UNKNOWN_BATCH`; `409 already-notifying`; `500 claim-lost` / `incomplete` / `resend-failed`; `502 DOWNSTREAM_REFUSED`; `503 STORE_UNAVAILABLE`; `504 DOWNSTREAM_UNAVAILABLE`; `501 command-not-wired` |
| Supersede what was recorded before an instant | `POST /operations/registers/supersede` | `{sharedBefore, dryRun?}` | `200` `{superseded, sharedBefore, dryRun}` | `400 missing-argument` / `unreadable-argument`; `400 SUPERSEDE_INSTANT_IN_FUTURE`; `400 SUPERSEDE_INSTANT_TOO_OLD`; `409 FLAG_ON`; `409 flag-unreadable`; `503 supersession-failed` |
| Pull the exception report | `POST /operations/exception-reports` | `{since?, email?}` | `200` `{runId, window, entries[], counts, truncated, delivered, outcome, durationMs}` | `400 unreadable-argument` (`since`); `409 email-output-disabled`; `409 email-output-not-wired`; `500 report-not-built`; `500 report-not-delivered` |

`501 command-not-wired` is not a refusal about the request: it is a pod deployed **without** the
generating half (`yotresultsdistribution.generation.enabled=false`) saying it does not hold the machinery,
which is exactly what the command it replaced answered there.

### The flag, per endpoint

There is **one** cutover lever, the App Configuration flag `YotResultsDistributionService`, and no endpoint
is a second one. Each reads it where the command it replaced read it, or more strictly — never more
loosely:

- **`GET /operations/flag`** reads it because that is what it is for.
- **`POST /operations/batches/generate`** reads it through the same `FeatureFlagGate` the 18:00 run
  uses, once per run and uncached, and refuses `409 flag-off` — unless the body carries
  `ignoreFlag: true`, which is the per-request break-glass `--ignore-flag` was. The override
  requires a **`batchId`**: `ignoreFlag` without one is `400 OVERRIDE_REQUIRES_BATCH`, so a
  break-glass is one batch and never a whole day. The override is recorded in the audit event and
  printed on the run report.
- **`POST /operations/registers/supersede`** reads it **although its command did not**, and is
  admitted only while it says **OFF** — `409 FLAG_ON` otherwise, `409 flag-unreadable` when it
  cannot be read, and no override at all. It also takes a `dryRun` and refuses an instant older than
  `yotresultsdistribution.operations.supersede-max-age` or in the future. An unconditional HTTP mutation that
  gives a period of registers up is a second lever however well authorised.
- **`POST /operations/exception-reports`** reads it **nowhere**, as `report-exceptions` did not: a
  pod that renders nothing still says what is wrong with what it recorded.
- The three listings read it nowhere.

### Calling it

Deployed, against the internal route, with the identity the gateway injects:

```bash
BASE=https://<internal-host>/yotresultsdistribution     # internal only; never exposed outside the estate
H='-H Content-Type:application/json'
```

Locally, `docker compose up -d app` and then `localhost:8082` — `docker-compose.yml` switches
`AUTHZ_HTTP_ENABLED` and `HTTP_AUDIT_ENABLED` **off** for the local loop (a laptop has no
usersgroups and no audit broker), so no header is needed and nothing is audited:

```bash
curl -s localhost:8082/operations/flag
# {"flag":"ON"}

curl -s "localhost:8082/operations/batches?date=2026-09-04"
# {"date":"2026-09-04","batches":[...]}

curl -s localhost:8082/operations/registers/recorded-while-off
# {"records":[...]}

curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8082/operations/batches/generate -d '{"date":"2026-09-04"}'
# 202 {"runId":"1b9e…","date":"2026-09-04","overridden":false}

curl -s -X POST localhost:8082/operations/batches/2f1c…/notify
# 200 {"batchId":"2f1c…","accepted":3,"failed":0,"state":"NOTIFIED","disposition":"settled"}

curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8082/operations/registers/supersede -d '{"sharedBefore":"2026-09-04T17:00:00Z","dryRun":true}'
# 200 {"superseded":47,"sharedBefore":"2026-09-04T17:00:00Z","dryRun":true}   <- nothing changed

curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8082/operations/exception-reports -d '{"since":"6h"}'
# 200 {"runId":"…","counts":{...},"entries":[...],"delivered":{"LOG":"delivered","EMAIL":"skipped"}}
```

`specs/005-operations-rest-api/quickstart.md` is the same walkthrough with every refusal shown.

### Deployment gates — the service has no operational surface until these land

The CLI is **removed**, so a pod deployed without these has no operational surface at all; and
because the identity header is an assertion, a pod deployed without gates 2 and 3 has a surface that
is **worse** than none. **This increment must not be deployed to STE before all five land**, and
none of them is in this repository:

1. An internal ingress / APIM route for `/operations/**` in the `cpp-aks-deploy` values, not exposed
   outside the estate.
2. The gateway **strips any client-supplied `CJSCPPUID` and injects the authenticated identity**.
   Without this, the authorisation is a caller's own claim about itself.
3. An Istio `AuthorizationPolicy` and a `NetworkPolicy` restricting `/operations/**` to that
   gateway, so no other workload in the mesh can reach it directly.
4. usersgroups reachable from the pod for the auth filter's identity client, with whatever network
   policy that requires.
5. The Artemis audit connection in the STE values — `CP_AUDIT_ENABLED=true` with the broker's hosts,
   port, credentials and TLS material from Key Vault.

**And one release gate that is in this repository and is not automated.** `./gradlew build` does
not prove that the *image* starts the application: `docker/startup.sh` lost its command dispatch in
increment 005 (FR-049) and the `e2e` suite that exercised the dispatch went with it, so the entry
point is covered by `./scripts/container-smoke.sh` and nothing else. **Run it by hand before every
release**, from a clean tree: it builds the image, brings compose up, waits for readiness and calls
`GET /operations/flag` with no arguments passed to the container anywhere. A green suite with a
broken entry point is exactly the shape of failure the script exists to catch.

### The switches, and what they default to

| Setting | Default | What it does |
|---|---|---|
| `AUTHZ_HTTP_ENABLED` | **`true`** | `cp-auth-rules-filter`. A deployment that says nothing is authorised. |
| `HTTP_AUDIT_ENABLED` | **`true`** | `cp-audit-filter-springboot`. On its own it builds nothing — see below. |
| `CP_AUDIT_ENABLED` | `false` | The audit **transport**. Every `audit.http.*` bean sits inside the auto-configuration this gates, so a values file without it serves the API unaudited, and the pod says so at WARN on start-up. Deployment gate 5. |
| `CP_AUDIT_INITIAL_CONNECT_ATTEMPTS` | `2` | How many times the audit JMS connection is attempted before the call is refused `503`. The library ships ten over a rising interval, which is **two minutes of a held servlet thread** before the caller is told — because the request event is published on the caller's own thread, before the action. Two attempts says the same thing in about two seconds; it changes how long a refusal takes, not what is refused. |
| `yotresultsdistribution.operations.enabled` | `true` | Whether the seven paths are served at all. Deployment shape, **not** a cutover lever. |

The first two are switched off only by local and test configuration — `docker-compose.yml` and
`application-test.yaml`, each saying why where it does it. Start-up **never refuses** on the
combination: an operator may turn either off, and the pod always comes up. What is refused is a
*value* that cannot mean what it says — an audit transport switched on with no host or a port
outside 1..65535, an audit filter switched on with no OpenAPI document to resolve, and an unusable
`supersede-max-age` or `lock-wait`.

One item is an **estate decision rather than a deployment step**, and is open:
`cp-audit-filter-springboot` captures every request header verbatim, and its own README says a
header allowlist should be agreed with the Audit team before rolling this out broadly.

## Prerequisites

- ☕️ Java 25 on `PATH` (the build resolves a 25 toolchain; use `./gradlew`, never a system Gradle)
- 🐳 Docker (the compose stack — Postgres and the Service Bus emulator with its SQL Server
  companion — plus the Redis, WireMock and, from increment 002, Artemis and file-service fixtures the
  `*IT` suites start for themselves)

## Quickstart

```bash
./gradlew build                 # compile + tests + PMD + Checkstyle (0 warnings) + JaCoCo gate
./gradlew test                  # test suite only; the *IT suites in it need Docker
./gradlew test -PexcludeTags=timing  # the same, without the two wall-clock cases (see below)
./gradlew checkstyleMain        # style gate on main sources
./gradlew pmdMain               # PMD on main sources; `check` runs pmdMain and pmdTest as well
./gradlew jacocoTestReport      # coverage report → build/reports/jacoco
./gradlew bootRun               # local run against docker-compose dependencies (see below)
./scripts/container-smoke.sh    # packaged-artefact smoke: compose up, readiness gate, then
                                # `GET /operations/flag` through that same gate; it reads and
                                # changes nothing
```

Two cases in `ExceptionReportEndToEndIT` carry `@Tag("timing")`: SC-006's "ten thousand rows
reported on inside ten seconds" and SC-008's "both schedules fired". They are real acceptance
criteria and they stay in the default selection, but their answer depends on how busy the host is -
so a developer building something else on the same machine can leave them out by name rather than by
disabling the suite. **`-PexcludeTags` is a local hatch only: CI runs every tag** - the one workflow
that tests, `ci-build-publish.yml`, runs `./gradlew jacocoTestReport check` and passes no
`excludeTags`, so nothing an acceptance criterion pins can be skipped on the way to a merge.

Local dependencies:

```bash
docker compose up -d postgres servicebus-emulator
YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB YOTRESULTSDISTRIBUTION_REFERENCEDATA_MODE=STUB \
  YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=00000000-0000-0000-0000-000000000000 ./gradlew bootRun
```

Both adapter modes default to `LIVE` — a service that has to be told to fetch payloads is one that
will be deployed not fetching them — so a bare `bootRun` refuses to start: startup demands upstream
endpoints and a `CJSCPPUID`, and compose has neither results nor reference data to call. The `app`
service in `docker-compose.yml` sets the same three variables for the same reason. Generation is
disabled by default in a bare `bootRun`; enabling it demands the file-service datasource, the
broker and the systemdocgenerator and notificationnotify endpoints, for the same reason. The `app`
service does enable it, against the committed stubs - `wiremock` for systemdocgenerator,
notificationnotify and Azure App Configuration, `fileservice-postgres` for the payload store,
`artemis` for `public.event` - with `yotresultsdistribution.feature.credential=local-test`, which is what
lets the real flag reader read a plain-HTTP stub at all; startup refuses that credential wherever
the endpoint names a real store or the pod is deployed. See
`specs/002-consolidate-progression-leg/quickstart.md` for the whole local loop.

The emulator's queue definition lives in `docker/servicebus-emulator/config.json`; the `*IT` test
fixtures mount the same file, so local, CI and deployed queue properties cannot drift. Compose is
local-only, and `bootRun` does not inherit compose environment variables — which is why the command
above passes them itself.

### Contribute to this repository

See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md). Repository working conventions live in
`CLAUDE.md`; the engineering constitution is `.specify/memory/constitution.md` and takes precedence
where they overlap.

## Licence

Released under the MIT Licence — see [LICENSE](LICENSE).
