# Feature Specification: Operations REST API, replacing the operations CLI

**Feature Branch**: `005-operations-rest-api`
**Created**: 2026-09-19
**Status**: Draft
**Input**: Design owner's decision, 2026-09-19 — "the six operator CLI commands become a REST
operations API and the CLI is removed", because that is the estate's Spring Boot shape and because
the estate starters give authentication and audit that `kubectl exec` cannot.

## Context

Increments 001–003 left this service with six operations commands baked into the image
(`generate-register`, `notify-register`, `list-batches`, `supersede-before`, `check-flag`,
`report-exceptions`), reached by `kubectl exec ... -- ./startup.sh <command>` and dispatched by
`docker/startup.sh` into `batch/cli/CliMain`. They were the only operational surface, deliberately:
constitution Principle III at 3.2.0 said this service had no REST API at all.

Three things are wrong with that, and none of them is about convenience:

1. **Nobody is named.** A command runs as the pod. The cluster records that somebody with exec
   rights on the namespace started a process; it does not record who regenerated a register date
   for children's court appearances, or who overrode the cutover flag while doing it.
2. **Nothing is audited.** The estate has an audit context and every other CPP Spring Boot service
   publishes its REST calls to it. An exec'd command publishes nothing.
3. **Authorisation is namespace-wide.** Exec rights are not the same thing as "Second Line
   Support", and there is no way to make them the same thing.

Constitution **4.0.0** (this branch's first commit, `d73ef50`) redefines Principle III to permit an
**operations API** under four conditions — authorised, audited, flag-gated **at least as strictly**
as the command it replaces was, and answering under Principle VII. This increment builds it and
removes the CLI.

It is a **port of an operational surface**, not a new capability. Every endpoint does what one
command did, takes what it took, refuses what it refused, and answers with the fields it printed.
Where an endpoint differs from its command it is because HTTP reaches further than `kubectl exec`
did, and every such difference makes it **stricter**, never looser: the regeneration takes the
nightly lock the CLI left to a runbook and runs asynchronously because no gateway holds a connection
for an hour; supersede is admitted only while the flag says OFF, gains a dry run and an age bound,
and has no override. Every difference is recorded in Assumptions and nowhere else.

**This increment is not a `doc/DEFECT-FIXES.md` event.** There is no legacy oracle for an
operational surface: neither the function app nor progression's leg had one, and replacing this
service's own CLI with this service's own API is not a deviation from either. No `C` or `P` row is
added, amended or flipped. A task that finds itself wanting a number has found a defect in 001–003.

## Constitution amendment proposal (Governance step 1)

The Governance section requires an amendment to be **proposed in a feature spec**, versioned, and
followed by a `/speckit-analyze` re-run against every in-flight feature spec. This section is that
proposal; the amendment itself landed as this branch's first commit, `d73ef50`, at version
**4.0.0**.

**Proposal**: redefine Principle III's closing clause. It said "The only HTTP this service exposes
is Spring Boot Actuator. There is no OpenAPI file ... Operational actions are a CLI in the image."
It now says: there is still **no business REST API**, and an **operations API** is permitted only
while every endpoint satisfies four conditions — (a) behind `cp-auth-rules-filter` with an explicit
allow rule naming the groups admitted, (b) audited by `cp-audit-filter-springboot`, (c) gated by the
`YotResultsDistributionService` flag **at least as strictly** as the CLI command it replaces was, with any
override recorded in the audit event and on the run report, and (d) answering under Principle VII.
`src/main/resources/yot-results-distribution-openapi.yaml` becomes a third contract this service owns.

**Version**: MAJOR (4.0.0). A previously forbidden endpoint is now permitted, the CLI the principle
named as the operational surface ceases to exist, and the repository gains an owned OpenAPI
document — three statements of existing practice a reader of 3.2.0 would get wrong.

**Why (c) says "at least as strictly" rather than "exactly where its command was"**: one endpoint
has to be gated *more* strictly than its command. `supersede-before` read the flag nowhere — it was
reachable only by someone with exec rights on the namespace, mid-rollback, with a runbook open. The
same action as an independently callable HTTP mutation makes this service give up a period of
registers irrespective of which implementation is live, which is a second lever however carefully it
is authorised. See FR-021 and assumption 11.

**Second amendment — 4.0.0 → 4.1.0 (2026-09-20), proposal.** Conditions (a) and (b) were written
as obligations on "every endpoint" with no environment named, and FR-045 and FR-053 enforce them as
start-up refusals on a **deployed** pod only — so the spec was granting itself an exemption from a
condition stated in the constitution, which the Governance section does not permit. **Proposal**:
state in Principle III where (a) and (b) are enforced — a start-up refusal naming the offending
setting wherever `yotresultsdistribution.servicebus.namespace` is set — and record the local loop as the one
exemption, at the constitution. **Version**: MINOR (4.1.0): the scope of a NON-NEGOTIABLE condition
is materially narrowed *and* a new obligation arrives with it (the refusals themselves), but no
endpoint that was forbidden becomes permitted and no deployed environment may do anything it could
not do before. The reason the exemption is recorded rather than removed is FR-045's, unchanged:
both filters are estate libraries needing an estate to talk to, a laptop has neither, and FR-044
serves the endpoints by default.

**Third amendment — 4.1.0 → 5.0.0 (2026-09-20), proposal.** 4.1.0 recorded *where* conditions (a)
and (b) are enforced: as a start-up refusal on a deployed pod, with the local loop as a named
exemption. The design owner has since decided that the refusal itself is wrong. `authz.http.enabled`
and `audit.http.enabled` are ordinary configuration an operator may turn on or off, and the pod
always comes up; the two switches are secure **by default** instead, at `true` in
`application.yaml`, and an environment that wants either off says so in its own configuration.

**Proposal**: relax conditions (a) and (b) so that each reads — the authorisation filter and the
audit publisher are enabled by **default** in the service's configuration and in every deployed
values file; switching either off is a deliberate configuration act of the operator, recorded in
that environment's configuration, never a code default and never inferred from the environment.
The "Where (a) and (b) are enforced" paragraph and the local-loop exemption both go with the
refusal they described: with the defaults secure and the switch an operator's, there is no
exemption left to grant and no discriminator left to draw.

**Version**: MAJOR (5.0.0). Relaxing a NON-NEGOTIABLE condition is a principle redefinition, not a
narrowing: 4.1.0 said a deployed pod **MUST refuse to start** with either filter off, and 5.0.0
says it starts. A deployed environment may now do something it could not do before, which is the
test this repository's Governance section sets for MAJOR, and a reader of 4.1.0 would get it wrong.
The exemption 4.1.0 added is removed rather than widened, because what it was an exemption *from*
no longer exists.

**Why the guarantee survives the relaxation**: the refusal was never the only thing holding
conditions (a) and (b) up, and it was the weakest of them. What holds them up is the default — a
deployment that says nothing about either switch is authorised and audited, which is the opposite of
the libraries' own defaults and is the change that mattered — together with the deployment gates
that require the audit connection in STE, and the allow rules and audit coverage every endpoint is
reviewed against. What the refusal added on top was a pod that would not start on a configuration an
operator had deliberately chosen, on a discriminator that made a laptop and a deployed pod two
different products.

**Fourth amendment — 5.0.0 → 5.0.1 (2026-09-20), proposal.** 5.0.0 says conditions (a) and (b) are
carried "by the defaults above and nothing else", and names one default for each: `authz.http.enabled`
and `audit.http.enabled`, both `true` in `application.yaml`. For (a) that is the whole truth. For (b)
it is not: `audit.http.enabled` builds nothing on its own. Every `audit.http.*` bean the starter
declares — the filter included — sits inside the `@AutoConfiguration` class `cp.audit.enabled` gates,
and this service ships that key `false`, because the transport's connection factory validates its
hosts and port while it is being constructed and a laptop has no audit broker. So a deployment that
says nothing is authorised, and is **not** audited.

**Proposal**: say so. Condition (b) is carried by **two** keys — `audit.http.enabled`, `true` in this
service's configuration, and `cp.audit.enabled`, which every deployed values file sets `true` with
its broker's hosts, port and credentials from Key Vault. A values file that omits the transport has
not met (b), and that is what the deployment review checks. Nothing about what is enforced changes:
there is still no cross-field start-up refusal, the pod still always comes up, and the value-shape
refusals of FR-053 are untouched.

**Version**: PATCH (5.0.1). A clarification of what an unchanged condition already required, not a
change to the condition: no obligation is added, removed or relaxed, and a reader of 5.0.0 who
deployed the audit transport was right. What was wrong was the sentence naming one key where two are
needed, which is exactly the kind of omission a deployment reads as permission.

**Governance step 3** — re-running `/speckit-analyze` against every in-flight feature spec and
updating or waiving each conflict — is task **T001** of this increment. It covers this spec and the
concurrently in-flight `specs/004-release-stale-batches`, which is read but never edited from this
branch; conflicts found there are recorded for the orchestrator, not fixed here. It was run once
for 4.0.0, again for 4.1.0 and again for 5.0.0; all three records are in T001's entry in
`tasks.md`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Second Line Support reaches every operator action over HTTP, under their own name (Priority: P1)

A named member of Second Line Support calls an `/operations/**` endpoint with their `CJSCPPUID`
identity. The call is authorised against their usersgroups membership, published to the audit
context as a request and a response, and answered with the same information the equivalent command
printed — as JSON, with bounded codes, counts and identifiers only.

**Why this priority**: it is the whole increment. Without it the CLI cannot be removed, and with it
every other story is a detail of one endpoint or another.

**Independent Test**: call each endpoint with a `CJSCPPUID` resolving to a member of "Second Line
Support" and assert the documented success shape; assert an audit event was published for the call.

**Acceptance Scenarios**:

1. **Given** a caller in "Second Line Support", **When** they `GET /operations/flag`, **Then** the
   answer is `200 {"flag":"ON"}` or `200 {"flag":"OFF"}` — the same verdict `check-flag` printed.
2. **Given** a caller in "Second Line Support", **When** they `GET /operations/batches?date=D`,
   **Then** the answer lists that date's batches with, per batch, its id, court house, state, record
   count and recipients — each recipient's address **masked exactly as `list-batches` masked it**
   and its outcome beside it.
3. **Given** any authorised call to any endpoint, **When** it is answered, **Then** an audit event
   for the request and one for the response have been published to the audit context, carrying the
   caller's identity and the action.
4. **Given** any endpoint, **When** it answers — success or refusal — **Then** the body carries no
   defendant detail, no unmasked recipient address, no exception message, no store's or far end's
   own words, and no value the caller supplied.

---

### User Story 2 - Nobody outside Second Line Support reaches any of it (Priority: P1)

A caller whose identity is not a member of "Second Line Support", or who presents no identity at
all, is refused before the endpoint's work begins. There is no endpoint with no rule, and no
default-allow.

**Why this priority**: the authorisation is the reason the surface is permitted to exist at all
(constitution Principle III(a)). An endpoint that is reachable by anyone is worse than the
`kubectl exec` it replaced, because it is reachable from further away.

**Independent Test**: for each endpoint, call it with an identity that is not in the group and
assert the refusal; call it with one that is and assert it is served. No endpoint passes both.

**Acceptance Scenarios**:

1. **Given** a caller whose groups do not include "Second Line Support", **When** they call any
   `/operations/**` endpoint, **Then** the call is refused and no application service is reached.
2. **Given** a request with no `CJSCPPUID` header, **When** it reaches any `/operations/**`
   endpoint, **Then** it is refused.
3. **Given** the deployed rule set, **When** it is read, **Then** there is exactly one allow rule
   per action, each naming "Second Line Support" and no other group, and no rule admitting an action
   by default.
4. **Given** actuator, **When** `/actuator/health` is called, **Then** it answers as it always has:
   actuator is not part of this surface and is not behind these filters.

---

### User Story 3 - Regenerating a date is still gated by the one lever, and now cannot collide with the nightly run (Priority: P1)

An operator asks for a register date to be regenerated. The endpoint reads the `YotResultsDistributionService`
flag through the same gate the 18:00 run reads it through, at the same point `generate-register`
read it, and refuses when it says off unless the operator has explicitly asked to override. An
override is an operator decision that is written down: in the audit event, with the caller's
identity, and on the run report. The endpoint then answers `202` and the work runs in the
background, on the generation scheduler's own thread, **holding the same lock the 18:00 run holds** —
which is the one rule the CLI did not have, and the reason it is taken rather than asked about.

**Why this priority**: this is the endpoint that changes production data, and it is the one the
cutover rule is about. Getting it wrong makes the flag stop being the one lever.

**Independent Test**: with the flag off, call generate without an override and assert the refusal
and that nothing was assembled; call it with the override and assert the work ran and the override
was recorded twice. Hold the nightly lock and assert the background run recorded that it could not
take it.

**Acceptance Scenarios**:

1. **Given** the flag says off and the body does not ask to override, **When** the endpoint is
   called, **Then** it refuses with `409 FLAG_OFF`, nothing is assembled, nothing is released and
   nothing is requested.
2. **Given** the flag is unreadable and the body does not ask to override, **When** the endpoint is
   called, **Then** it refuses with `409 FLAG_UNREADABLE` — fail-closed, exactly as the nightly run
   fails closed.
3. **Given** the flag says off and the body asks to override, **When** the endpoint is called,
   **Then** the regeneration proceeds **and** the override is recorded in the audit event with the
   caller's identity and on the run report as `reason=overridden`, exactly as `generate-register`
   printed and counted it.
4. **Given** an accepted regeneration, **When** the endpoint answers, **Then** it answers `202` with
   a run id and the work continues in the background; the tally the command printed is on the run
   report line, and what the run did to the day is read back through `GET /operations/batches`.
5. **Given** the 18:00 run holds the register-generation lock, **When** a background regeneration
   tries to take it, **Then** it does not get it, does nothing, and the refusal is that run's
   recorded outcome; and in the other order, a scheduled run that starts while a background
   regeneration holds the lock stands aside by the mechanism it already uses.
6. **Given** two regenerations for the same date on two replicas, **When** both run, **Then** the
   loser's release returns no rows and its assemble is refused by the live-key index, and that
   refusal is a bounded recorded outcome — never an unexplained failure.

---

### User Story 4 - Every refusal says which kind it is, and none of them quotes the caller (Priority: P1)

The CLI's three exit codes meant three different things to a runbook step: `0` did it, `1` declined
and changed nothing, `2` tried and could not. The API keeps that distinction, because a runbook that
retried a refusal as a failure would be an operator overriding the cutover flag by accident.

**Why this priority**: the distinction is load-bearing operationally, and the "no input echoed"
rule is Principle VII. Both are cheap to lose and expensive to notice.

**Independent Test**: drive each endpoint into each of its refusals and assert the status, the
bounded reason and the absence of any supplied value in the body and in the log.

**Acceptance Scenarios**:

1. **Given** a request whose argument cannot be read (a date that is not a date, an id that is not
   an id, an instant that is not an instant), **When** it is answered, **Then** the status is `400`,
   the body names the **argument** by this service's own name for it, and the body does **not**
   contain the value that was sent.
2. **Given** a request missing an argument the endpoint cannot do without, **When** it is answered,
   **Then** the status is `400` with a bounded reason.
3. **Given** a state refusal — the flag is off, the schedule is running, the e-mail output is
   switched off — **When** it is answered, **Then** the status is `409` with a bounded reason and
   **nothing has changed**.
4. **Given** an endpoint that tried and could not finish, **When** it is answered, **Then** the
   status is `500` with the bounded reason the command printed, and the log line names the failing
   class and not its message.
5. **Given** any refusal, **When** the response is read, **Then** it is a `ProblemDetail` whose
   fields are bounded codes, counts and identifiers — no free text, no stack trace, no exception
   message.

---

### User Story 5 - The image starts the application, full stop (Priority: P1)

The CLI is removed: the command classes, the mode switch that kept a command's JVM from consuming
deliveries, the property behind it, and the entrypoint's command dispatch. A container started with
arguments no longer means anything special; a container started at all starts the application.

**Why this priority**: two operational surfaces is worse than either, and the mode switch existed
only to protect a process that no longer exists. Leaving it behind leaves a second way to start a
pod that does not consume.

**Independent Test**: build the image, start it, and assert the application starts; assert the
repository contains no `batch/cli/`, no `CliModeConfig`, no `yotresultsdistribution.cli` and no dispatch in
`docker/startup.sh`.

**Acceptance Scenarios**:

1. **Given** the built image, **When** it is started with no arguments, **Then** the application
   starts exactly as it does today.
2. **Given** the repository, **When** it is searched, **Then** `batch/cli/`, `config/CliModeConfig`,
   the `yotresultsdistribution.cli` property and every reference to them are gone, and every conditional
   that read that property — the nine FR-048 enumerates, from the consumer and the four schedulers
   to the operations wiring this increment added — is unconditional again.
3. **Given** the documentation, **When** it is read, **Then** no page instructs an operator to
   `kubectl exec` a command; the quickstart's CLI examples are `curl` examples.

---

### User Story 6 - The remaining four actions answer exactly as their commands did (Priority: P2)

Resending a batch's owed recipients, superseding what was recorded before an instant, listing what
was recorded while the flag was off, and pulling the exception report for a window: four endpoints,
four commands, same arguments and same fields.

**Why this priority**: they are the rest of the surface. P2 rather than P1 only because each is
independently shippable once the filters and the error mapping of P1 and P4 exist.

**Independent Test**: each endpoint against a stubbed application service, asserting the fields the
command printed.

**Acceptance Scenarios**:

1. **Given** a batch with recipients no e-mail has been accepted for, **When** the notify endpoint
   is called for it, **Then** those recipients and only those are re-requested, and the answer
   carries the batch id, the accepted and failed counts, the batch state and the disposition.
2. **Given** another notifier already holds the batch's claim, **When** the notify endpoint
   answers, **Then** the status is `409 ALREADY_NOTIFYING` — the claim `RegisterNotifierService`
   already takes is what arbitrates two concurrent calls, and nothing new is invented to do it.
3. **Given** a disposition that leaves the batch unsettled after this call tried — `CLAIM_LOST` or
   `INCOMPLETE` — **When** the notify endpoint answers, **Then** the status is `500` carrying that
   disposition as its bounded reason: the call tried and could not finish, which is what those two
   mean.
4. **Given** a supersede request while the flag says OFF, **When** it is answered, **Then** the
   answer carries the count superseded and the instant it was taken from; an absent instant is a
   `400` and is **never** defaulted; a `dryRun` request answers the count and supersedes nothing; an
   instant in the future, or older than the configured bound, is a `400`; and the audit event
   carries the count.
5. **Given** the recorded-while-off listing, **When** it is answered, **Then** each row carries its
   record id, hearing id, register date and the flag state it was recorded under.
6. **Given** an exception-report request, **When** it is answered, **Then** the answer carries the
   window, the entries (each an identifier-only row), the per-kind counts, whether the list was
   truncated, what each sink did, the run id and the duration — the same fields the command printed
   — and the endpoint has read the cutover flag **nowhere**.
7. **Given** an exception-report request asking for e-mail while the e-mail output is switched off,
   or on with no sink behind it, **When** it is answered, **Then** the status is `409` carrying
   `EMAIL_OUTPUT_DISABLED` or `EMAIL_OUTPUT_NOT_WIRED` respectively.

---

### Edge Cases

- **A path or method nobody mapped.** Answered as the framework answers it, and never with a stack
  trace or the path that was tried.
- **A body that is not JSON, or JSON of the wrong shape.** A `400` with a bounded reason and no
  echo of the body.
- **An unknown field in a request body.** Refused, not ignored: the request contract is closed for
  the same reason the inbound queue message is — tolerating drift hides it until it matters.
- **An identity that usersgroups cannot be asked about** (the identity service is down or slow).
  The call is refused rather than admitted; fail-closed is the only safe direction for an
  authorisation decision, and it is the same direction the flag fails in.
- **The audit context is unreachable.** Decided in the plan from the starter's own behaviour, and
  recorded there: a call that cannot be audited must not quietly proceed as though it had been.
- **A regeneration that answers a caller who has gone away.** The work is not undone: the API has no
  equivalent of the CLI's "the report could not be written" case, because the answer is written once
  at the end rather than streamed line by line — which removes a whole class of the CLI's failure
  handling rather than porting it.
- **Two operators calling generate for the same date at once.** Behaves exactly as two `kubectl
  exec` runs did: the store's own claims and the assembler's in-flight deferral decide, and the
  endpoint invents no new locking of its own beyond the nightly-run check.
- **An exception report whose sinks did not all take it.** The report was built and is returned in
  full; the failure to deliver it is said separately and is not dressed up as a success.
- **Two notify calls for one batch at once.** Decided by the claim `RegisterNotifierService` already
  takes (`register_batch_notifier_claim_chk`, `V2`) and its four dispositions — the sole-committer
  guarantee the separate CLI JVM never provided in the first place. Nothing new arbitrates it.
- **Two regenerations for one date, on two replicas.** The background runs contend for the
  register-generation lock, so only one proceeds at a time; if one nonetheless reaches the store
  first, the loser's `releaseFailed` returns no rows and its assemble meets the live-key index. That
  MUST surface as a bounded refusal recorded on the run, not as an unexplained failure.
- **A public document event arriving during a regeneration.** Applied exactly as it is during a
  scheduled run — the listener is running in this pod, which it was not in a CLI JVM.
- **A regeneration that outlives the request.** Expected, by design: the request answers `202` and
  the work continues. A client that hangs up, a gateway that times out, and a caller that retries
  all leave the run untouched; the retry is refused or deduplicated rather than starting a second
  run over the same date.

## Requirements *(mandatory)*

### Functional Requirements

**The surface**

- **FR-001**: The service MUST expose exactly seven endpoints under `/operations/**`, one per
  operator action, and no other HTTP path besides Spring Boot Actuator and the container's own
  `/error` forward — which serves no request of its own and exists so that a refusal a filter took
  by `sendError` is rendered from this surface's status map rather than by the framework's default
  body (FR-027, `api/OperationsErrorAttributes`).

  *On the numbering*: these requirements are grouped by subject, and a group's numbers are not
  contiguous. FR-029 to FR-032 are **vacant**: they were the first draft's "Removing the CLI" group
  and became FR-047 to FR-051 when the two design reviews were folded in (`342b5aac`) and the
  groups in between grew. They are left unused rather than reassigned, because a requirement number
  is quoted from task records, review findings and the tasks file and must keep meaning what it
  meant.
- **FR-002**: Every endpoint MUST be described in an OpenAPI 3 document at
  `src/main/resources/yot-results-distribution-openapi.yaml`, owned and versioned by this repository, covering
  its request, its success shape and every bounded `reason` it can refuse under. A contract test
  MUST assert that the controllers and the document agree in both directions.
- **FR-003**: The HTTP layer MUST be an inbound adapter in `uk.gov.hmcts.cp.yotresultsdistribution.api`: it
  parses, calls application services, and maps the answer. It MUST NOT hold a repository, an HTTP
  client, a broker client or a business decision, and no logic MUST be rewritten on the way in —
  where a command class held orchestration, that orchestration moves into an application service
  unchanged rather than into a controller.
- **FR-004**: The endpoints MUST add no capability the CLI did not have. No new selection, no new
  filter, no new field that the command did not print.

**Authorisation and audit**

- **FR-005**: Every `/operations/**` request MUST be authorised by `cp-auth-rules-filter` against
  the caller's usersgroups membership, with identity taken from the `CJSCPPUID` header.
- **FR-006**: There MUST be exactly one explicit allow rule per action in
  `src/main/resources/acl/operations-rules.drl`, each admitting the group **"Second Line Support"**
  and no other. An action with no rule MUST be refused; there MUST be no default-allow.
- **FR-007**: A caller who is not in the group, and a request with no identity, MUST be refused
  before any application service is reached and before anything is read or changed.
- **FR-008**: Every `/operations/**` request and its response MUST be published as an audit event to
  the audit context by `cp-audit-filter-springboot`.
- **FR-009**: Actuator MUST be unaffected: not behind these filters, not in the OpenAPI document,
  and answering exactly as it does today.

**The cutover lever**

- **FR-010**: `POST /operations/batches/generate` MUST read the `YotResultsDistributionService` flag through
  the same gate the 18:00 run uses, at the same point `generate-register` read it, with no cache,
  and MUST refuse — changing nothing — when it says off or is unreadable and the request did not ask
  to override.
- **FR-011**: An override (`ignoreFlag: true`) MUST be recorded in the audit event **and** on the
  run report — the same `RunReport` line the 18:00 run writes, distinguished by an operator trigger
  — and MUST keep the existing override log line and counter the gate already emits. The
  regeneration endpoint is the **only** endpoint with an override; no other endpoint accepts one.
- **FR-012**: `GET /operations/flag` MUST read the flag (that is its purpose).
  `POST /operations/registers/supersede` MUST read it under FR-021. `POST
  /operations/exception-reports` MUST read it **nowhere**, as its command did. No other endpoint
  MUST read it.
- **FR-013**: No endpoint MUST decide which implementation is live, and no endpoint MUST mutate
  register state irrespective of the flag. The flag remains the one lever.

**The seven endpoints**

- **FR-014**: `GET /operations/flag` MUST answer `200` with the flag's verdict in all three cases:
  `ON`, `OFF`, and `UNREADABLE` with the bounded reason beside it. The three outcomes `check-flag`
  had are three **readings**, not three service states — the endpoint answered, and a `503` here
  would make the gateway read this service as down and may cost the caller the body that says why
  the flag could not be read.
- **FR-015**: `GET /operations/batches?date=D` MUST answer that register date's batches, each with
  its id, court house, state, record count and its recipients; every recipient address MUST be
  masked by the **same rule** `list-batches` masked by, and the unmasked address MUST NOT appear in
  the response or in any log line.
- **FR-016**: `GET /operations/registers/recorded-while-off` MUST answer the records recorded while
  the flag was off, each with its record id, hearing id, register date and recorded flag state.
- **FR-017**: `POST /operations/batches/generate` MUST take a required register date and the
  optional narrowings `courtHouse`, `batchId` and `recordedBefore`, plus `ignoreFlag` (default
  `false`). **`ignoreFlag: true` MUST be accepted only when the request names a single `batchId`**;
  with `ignoreFlag: true` and no `batchId` the request MUST be refused `400
  OVERRIDE_REQUIRES_BATCH`. Overriding the cutover flag is a break-glass for one batch at a time:
  re-driving a whole day under override is one call per failed batch, deliberately. Without
  `ignoreFlag`, a date-wide regeneration stays allowed while the flag is ON, exactly as the command
  allowed it. It MUST be **asynchronous**: it validates the request, reads the flag, records the run,
  answers `202` with a **run id**, and does the work on the generation scheduler's single-threaded
  executor. It MUST NOT do the work inline. The CLI POSTed to systemdocgenerator under a
  sixty-minute run deadline; behind an ingress with a 30–240 second timeout an inline endpoint gives
  the caller a `504` while the pod keeps working, and the retry that follows meets the live-key
  index and fails a run that had succeeded.
- **FR-018**: The background regeneration MUST **acquire** the register-generation ShedLock — the
  same lock name the 18:00 run takes — for the whole run, by a non-blocking attempt, and where it
  cannot get it within a bounded wait it MUST do nothing and record that as the run's outcome.
  Asking whether the lock is held and then acting would race the scheduler; taking it cannot. In the
  other order, a scheduled run that starts while a regeneration holds the lock MUST stand aside by
  the mechanism it already uses.
- **FR-019**: What the run did MUST be observable without a second surface. Three places carry it
  between them, and no one of them is asked to carry all of it:
  - the same `RunReport` line the 18:00 run writes, carrying the run id, an operator trigger,
    `reason=overridden` where the flag was overridden, and the **counts** the command printed —
    released, registers, batches, requested, deferred, and how many batches were withheld;
  - the requesting leg's own lines, under that same run id, for **each batch's** id and state, and
    a `WARN` per withheld batch naming its bounded reason. The run line is one line and stays one
    line: a per-batch tally on it would be a line whose width is a night's workload;
  - `GET /operations/batches?date=D` for the day's state afterwards, which is the only place a
    per-batch **record count** is carried — no log line has one.

  The whole of it is correlated by the one run id the caller was answered with, which is what makes
  three places one account rather than three.
- **FR-020**: `POST /operations/batches/{batchId}/notify` MUST re-request the recipients of that
  batch no e-mail has been accepted for **and only those**, synchronously, and MUST answer `200`
  with the accepted and failed counts, the batch state and the disposition when the disposition is
  `SETTLED`. `ALREADY_NOTIFYING` MUST answer `409` — another notifier holds the claim and this call
  changed nothing. `CLAIM_LOST` and `INCOMPLETE` MUST answer `500` carrying that disposition as the
  bounded reason — the call tried and could not finish. A batch id that names no batch MUST answer
  `404`, distinguished from a store outage, which MUST answer `503`.
- **FR-021**: `POST /operations/registers/supersede` MUST take a required instant and answer,
  synchronously, `200` with the count superseded and the instant it was taken from. The instant MUST
  NOT be defaulted: an absent one is a refusal. It MUST additionally:
  - be admitted **only while an uncached read of the `YotResultsDistributionService` flag says OFF** — the
    flag ON refuses `409 FLAG_ON`, an unreadable flag refuses `409 FLAG_UNREADABLE` (fail-closed),
    and there is **no override**: this endpoint makes the service give up a period of registers, and
    doing so while it is the live implementation is a second lever;
  - support `dryRun` (default `false`), which answers the count that **would** be superseded and
    supersedes nothing;
  - refuse `400` an instant in the future, and one older than `yotresultsdistribution.operations.supersede-max-age`
    (default 30 days) — an unbounded irreversible mutation is one keystroke from giving up the
    estate's whole history of registers;
  - carry the count in the audit event.
- **FR-022**: `POST /operations/exception-reports` MUST take an optional window start (`since`) and
  `email` (default `false`), MUST answer `200` synchronously — the report is built and every asked sink has been
  delivered to before it answers — with the window, entries, per-kind counts, truncation, per-sink
  delivery, run id and duration, and MUST refuse `409 EMAIL_OUTPUT_DISABLED` or
  `409 EMAIL_OUTPUT_NOT_WIRED` when e-mail is asked for and unavailable. With no `since` the window
  MUST run from the previous scheduled report, exactly as the command computed it.

**Refusals, failures, and what a response may carry**

- **FR-023**: The CLI's three outcomes stay three distinguishable families, refined where HTTP has a
  more accurate code than an exit status did. A runbook MUST still be able to tell "declined, change
  nothing, do not retry" from "tried and could not":
  - **`400`** — a malformed or missing argument: a date that is not a date, an id that is not a
    UUID, an instant that is not an instant, a body that will not parse, a body with an unknown
    field.
  - **`404`** — an identifier that is well-formed but names nothing (an unknown batch).
  - **`409`** — a state refusal, changing nothing: the flag is off (or on, for supersede), the
    generation lock is held, the e-mail output is switched off or not wired, a notification
    disposition left the batch unsettled.
  - **`503`** — a dependency this endpoint exists to write through is unavailable: the store will
    not answer. **Not** the flag: an unreadable flag is a reading (`GET /operations/flag`) or a
    refusal (`409 FLAG_UNREADABLE`), never a claim that this service is down.
  - **`502` / `504`** — a downstream platform contract refused or did not answer in time, under a
    bounded reason.
  - **`500`** — the call tried and could not finish: an unexpected defect, or one of the two
    notification dispositions that leave the batch unsettled. A `500` this service can explain as a
    state refusal is a `409` it failed to classify. Where a `500` follows work that was partly done,
    the `ProblemDetail` MUST carry the partial tally in bounded properties — "the day stands as
    whatever this run had already written down" is the one thing the operator needs.
- **FR-024**: Every non-`2xx` response MUST be a `ProblemDetail` carrying a **bounded** `reason`
  code from a closed set named in the OpenAPI document — the code the command printed where there
  was one.
- **FR-025**: No response and no log line MUST **echo** a value the caller supplied. A refusal over
  an argument names the **argument**, by this service's own name for it, never the value
  (constitution Principle III(d)); a value that would not read reaches no body, no log line and no
  metric label at all. What a **success** record may carry is this service's own **parse** of an
  identifier or an instant — the batch it resolved, the register date it read, the bound it
  superseded from, each of them a field the OpenAPI document types as a uuid, a date or a
  date-time. It is rendered from the parsed value and never from the characters that arrived, and a
  request made in a non-canonical but parseable spelling therefore comes back in the canonical one.
  That is what tells a parse from an echo, and it is how each of these fields is tested, one case
  per field: pinning the identical characters back would prove nothing either way. A **refusal**
  MUST carry no caller value but one — the parsed identifier of the thing it refused about, on the
  same terms, so that an operator told `409 already-notifying` is told which batch. That is not the
  value-naming Principle III(d) bans: what is banned is quoting back an argument that would not
  read, and an argument that would not read never parses, so it can never reach a refusal by this
  route. No exception message and no throwable this service did not write MUST reach either.
- **FR-026**: No response MUST contain defendant detail of any kind, an unmasked recipient address,
  a payload, a register document or a fragment of one.
- **FR-027**: An unknown or unmapped path or method MUST answer as the framework does, without a
  stack trace and without quoting the path.
- **FR-028**: A request body carrying an unknown field MUST be refused with a bounded reason, not
  silently ignored.

**The trust boundary**

- **FR-035**: The `CJSCPPUID` on a request is an **assertion**, and `cp-auth-rules-filter`
  authorises whatever it is given: any workload inside the mesh could assert a Second Line Support
  identity. It MUST therefore be treated as an authenticated claim made by the **gateway**, never by
  the caller. The internal ingress/APIM route MUST strip any client-supplied `CJSCPPUID` and inject
  the authenticated identity, and `/operations/**` MUST be reachable **only** from that gateway,
  enforced by an Istio `AuthorizationPolicy` and a `NetworkPolicy`. These are deployment gates
  (below); the spec states them because the endpoints are unsafe without them and because nothing in
  this repository can assert them.
- **FR-036**: The action a request is authorised against MUST be **derived by this service from the
  path and method**, and MUST override whatever action header the caller sent. A caller authorised
  for one action MUST NOT be able to reach another by sending a header.
- **FR-037**: Every authorisation decision MUST deny by default: a missing or blank identity, an
  identity service that cannot be asked, an action with no rule, and a rule that does not match all
  MUST refuse. No path admits an unauthenticated or unauthorised caller by falling through.
- **FR-038**: The `CJSCPPUID` value MUST NOT appear in an HTTP access log line or in any application
  log line. It belongs in the audit event, which is the one place the caller is named on purpose.
- **FR-039**: The authorisation behaviour MUST be proven against the **real filter** — the starter
  wired as deployed, with the usersgroups identity service stubbed at the HTTP boundary — and not
  only against a stubbed identity client. A test that mocks the decision proves the test.

**Concurrency**

- **FR-040**: The endpoints run inside a **live pod**, with the Service Bus consumer, both
  schedulers and the public-event listener all running — which the removed CLI mode switched off.
  Every endpoint MUST therefore be safe against the running legs, and MUST reproduce none of their
  logic: it calls the same claimed application services and relies on the claims they already take.
- **FR-041**: Two simultaneous calls to the notify endpoint for one batch MUST produce the
  dispositions the notifier's own claim already defines, and MUST NOT produce a second e-mail to a
  recipient the first call reached.
- **FR-042**: A regeneration MUST NOT proceed concurrently with a scheduled run, in either order
  (FR-018), and a public document event arriving during a regeneration MUST be applied exactly as it
  is during a scheduled run.
- **FR-043**: A failure **after** an endpoint's side effects — a response that cannot be
  serialised, a client that has gone away — MUST NOT be reported to anyone as a refusal, and MUST
  NOT cause the work to be attempted again automatically.

**Removing the CLI**

- **FR-047**: `batch/cli/` (every class and every test), `config/CliModeConfig` and its test MUST be
  removed.
- **FR-048**: The `yotresultsdistribution.cli` property MUST be removed, and **every** conditional that
  reads it MUST become unconditional. The enumeration is made against the tree the removal lands
  on, not against this list, because later phases and the merge of 004 both add readers; as the
  removal found them there are **nine**, each a class-level
  `@Conditional(CliModeConfig.NotCliMode.class)`: `inbound/ServiceBusConsumerConfig`,
  `config/SchedulingConfig`, `config/SchedulingInfrastructureConfig`,
  `config/ReportSchedulingConfig`, `config/IntakeSweepConfig`, `config/BatchSweepConfig`,
  `config/PublicEventsConfig`, `config/OperationsWebConfig.GenerationBackedOperations` and
  `api/BatchesController` — the last two written by this increment itself. (`config/ProcessedLogConfig`
  named the condition in a javadoc and carried none: a prose correction, not a conditional.) A
  stale enumeration is a conditional left behind on a property that no longer exists, so T054's
  record carries the same list.
- **FR-049**: `docker/startup.sh` MUST lose its command dispatch: the entrypoint starts the
  application and nothing else, and an argument is no longer special.
- **FR-050**: Every document that instructs an operator to run a command MUST instruct them to call
  an endpoint instead: `README.md`'s operations section, `specs/002-consolidate-progression-leg/quickstart.md`'s
  CLI examples (replaced by `curl`), and the container smoke script.
- **FR-051**: The removal MUST land **after** every endpoint has a passing test, in a phase of its
  own — never interleaved, so that at no commit is there neither surface.

**Settings**

- **FR-033**: The starters' settings MUST be typed and validated at start-up like every other
  setting this service has: the identity endpoint the auth filter resolves groups through, the
  audit switch and the OpenAPI document location the audit filter resolves path parameters from,
  and the audit transport's connection.
- **FR-034**: No secret, no connection string and no static credential MUST appear in a committed
  value or an environment default.
- **FR-044**: The operations API MUST have a deployment-shape switch of its own,
  `yotresultsdistribution.operations.enabled` (default **true**), which decides whether the endpoints are
  served — and nothing else. It is **not** a cutover lever and MUST NOT be documented as one.
- **FR-052**: On a pod where the generation half is switched off
  (`yotresultsdistribution.generation.enabled=false`), the endpoints that need its beans — generate, notify
  and the batch listing — MUST answer `501` with the bounded reason `COMMAND_NOT_WIRED`, which is
  exactly what the CLI answered on such a pod, rather than a `404` that reads as a mistyped URL or a
  `500` about a missing bean. The endpoints that do not need them — the flag, the recorded-while-off
  listing, supersede and the exception report — MUST be served normally.
- **FR-045**: `authz.http.enabled` and `audit.http.enabled` are **ordinary configuration an
  operator may set**, and both MUST default to `true` in `application.yaml`
  (`${AUTHZ_HTTP_ENABLED:true}` and `${HTTP_AUDIT_ENABLED:true}`), so that a deployment which says
  nothing is authorised, and is audited wherever the audit transport is on beside them (the
  paragraph below). Switching either off is a deliberate act recorded in that
  environment's own configuration: the local loop does it in the compose environment and the `test`
  profile does it in `application-test.yaml`, each with the reason written beside it.
  **Condition (b) needs the audit transport with it.** `audit.http.enabled` switches on a filter
  that is only built where `cp.audit.enabled` is on — every `audit.http.*` bean the starter declares
  sits inside the `@AutoConfiguration` class that key gates — and this service ships
  `cp.audit.enabled: ${CP_AUDIT_ENABLED:false}`, because the transport's connection factory
  validates `cp.audit.hosts` and `cp.audit.port` while it is being constructed and a laptop has no
  audit broker. Every deployed values file MUST therefore set `CP_AUDIT_ENABLED=true` beside the
  two switch defaults, with the broker's connection from Key Vault (deployment gate 5); a values
  file that omits it serves the operations API **unaudited** and has not met condition (b). Because
  nothing refuses that combination, the service MUST **say** it: one WARN at start-up naming
  `audit.http.enabled` and `cp.audit.enabled`, since the filter that would have published is never
  constructed and the one that is swallows its own publishing failures.
  **Start-up MUST NOT refuse on the combination**: there is no cross-field rule tying
  `yotresultsdistribution.operations.enabled` to either switch, and no environment discriminator deciding
  where such a rule would apply. A pod started with either switch off — or with both off — MUST come
  up and serve whatever it was configured to serve. An operator who wants to see what the endpoints
  do with a filter out of the way is entitled to, and a service that refuses to start on a
  configuration an operator chose is a service that cannot be operated.
  *Supersedes the earlier reading of this requirement, which refused start-up on a deployed pod
  serving the operations API with either filter off; design owner, 2026-09-20.* What the refusal was
  protecting is protected where it belongs instead: the defaults above, the deployment gates that
  require the audit connection in STE, and condition (a)/(b) of Principle III, which is an
  obligation on how an environment is configured rather than on what this service's start-up
  tolerates.

- **FR-053**: The **value-shape** validations stay, and they are the only start-up refusals these
  settings can earn. They are about a value that cannot mean what it says, never about one switch's
  value given another's:
  - `audit.http.openapi-rest-spec` MUST be set to a non-blank value wherever **both** the audit
    transport (`cp.audit.enabled`) and the HTTP filter (`audit.http.enabled`) are on, which is where
    the parser that globs is built — every `audit.http.*` bean sits inside the `@AutoConfiguration`
    class the transport's key gates, so the filter on over a transport that is off resolves nothing.
    It is required there because the filter resolves the value as a **suffix** glob and an unset
    value globs for `*null`,
    matching nothing and failing the refresh without naming either this service or the key. The
    value MUST be uniquely scoped to this service's own document, so that exactly one file on the
    classpath can match it.
  - `cp.audit.hosts` MUST name at least one non-blank host, and `cp.audit.port` MUST be a whole
    number in **1..65535**, wherever the audit transport is switched on — the audit filter swallows
    its own publishing failures, so a transport pointed at nothing publishes nothing and says so
    only in a log line, and the library's own `validateProps` checks `hosts.isEmpty()` and
    `port > 0` and nothing else.
  - `yotresultsdistribution.operations.supersede-max-age` MUST be positive and
    `yotresultsdistribution.operations.lock-wait` MUST NOT be negative — an unusable value is unusable
    wherever it is set.

  Each refusal MUST name the offending setting and MUST NOT quote the offending value back where it
  could be a secret.


- **FR-046**: The audit event for an operations call MUST carry **bounded fields** — the action, the
  outcome, and for a regeneration whether the flag was overridden — and MUST NOT carry a raw request
  or response body. The generic filter cannot infer any of that from a body, so the fields are
  supplied through the starter's own seam rather than left to it.

### Key Entities

- **Operator action** — one of seven named things support can do, each with a stable action name of
  the form `yot-results-distribution-operations.<verb>`, an allow rule, an endpoint and an OpenAPI entry.
  Nothing else is an action.
- **Caller identity** — the `CJSCPPUID` on the request, resolved to a usersgroups membership. It is
  an identifier, and it is the only thing about the caller that is written down.
- **Refusal** — a bounded reason code with a status family: the same codes the CLI printed
  (`FLAG_OFF` and the gate's other codes, `SCHEDULE_RUNNING`, `EMAIL_OUTPUT_DISABLED`,
  `EMAIL_OUTPUT_NOT_WIRED`, the four notification dispositions, `MISSING_ARGUMENT`,
  `UNREADABLE_ARGUMENT`, and the four failure codes `generation-failed`, `resend-failed`,
  `listing-failed`, `supersession-failed`, `report-not-built`), normalised in the OpenAPI document.
- **Audit event** — what the audit context receives for every request and every response, carrying
  the caller, the action, and — where one was given — the fact of a flag override.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every one of the six commands has an endpoint that takes the same arguments and
  answers the same fields; a reviewer can put the command's output line and the endpoint's JSON side
  by side and find no field in one that is missing from the other.
- **SC-002**: 100% of `/operations/**` endpoints are refused for a caller outside "Second Line
  Support", proven by a test per endpoint rather than by inspection of the rule file.
- **SC-003**: 100% of `/operations/**` endpoints produce an audit event, proven by a test.
- **SC-004**: Zero responses and zero log lines contain a value the caller supplied, proven by the
  privacy sweep the repository already runs plus a case per refusal.
- **SC-005**: With the flag off and no override, a generate call changes nothing — no batch row, no
  released record, no render requested — proven by a test that asserts the store was not written to.
  The same holds for supersede with the flag **on**.
- **SC-006**: A background regeneration does nothing while the nightly lock is held, and a scheduled
  run stands aside while a regeneration holds it — both orders proven by a test that holds the lock.
- **SC-010**: Start-up **does not** refuse the operations API being enabled with either estate
  filter off — an operator who switched one off has said what they meant (FR-045, assumption 9).
  What it refuses is a value that cannot mean what it says (FR-053), and what it does about the
  combination is say it: a pod with `audit.http.enabled` on and `cp.audit.enabled` off comes up,
  serves, and writes one WARN naming both settings. Proven by `ApplicationContextRunner` cases -
  one that such a context starts, and one that the WARN is on it.
- **SC-011**: No audit event carries a raw request or response body, proven by a test over the
  publisher seam; and the privacy sweep covers controller responses and `ProblemDetail` bodies as
  well as log statements.
- **SC-012**: The component-scan exclusion of `uk.gov.hmcts.cp.filter.audit` is pinned by a context
  test — without it the application does not start at all, and the failure is a bean-definition
  error nobody would read as a component-scan clash.
- **SC-007**: After the removal phase, a repository-wide search for `batch/cli`, `CliModeConfig`,
  `yotresultsdistribution.cli` and the six command names finds nothing outside this spec, the constitution's
  history and the earlier increments' own records.
- **SC-008**: The built image starts the application when started with no arguments, and the
  container smoke exercises an endpoint rather than a command.
- **SC-009**: The build stays green under the existing gates, with the coverage thresholds unchanged
  (LINE ≥ 0.88, BRANCH ≥ 0.85) — the ratchet is never loosened to admit a controller.

## Out of Scope (this increment)

- **Any business endpoint.** No hearing submitted over HTTP, no register read out, no batch created
  by a caller, no status or replay surface. Widening one of the seven into a query surface is the
  same thing by degrees, and needs a constitution amendment.
- **A second caller group.** "Second Line Support" only, on every endpoint. Adding a group is a rule
  change with its own review, not a decision this increment takes in advance.
- **A user interface.** The endpoints are called by support with a tool, exactly as the commands
  were run by support with a shell.
- **Changing what any action does.** The generation, notification, supersession and reporting
  behaviour is 002's and 003's, and is not touched.
- **The infrastructure wiring.** The ingress/APIM route for `/operations/**`, the usersgroups
  network path, and the Artemis audit connection live in the sibling infra repositories. They are
  **gates on this increment's deployment**, listed below, not work in this repository.
- **Increment 004's changes** to the reconciler and the generation settings, which land on their own
  branch and merge first.

## Deployment gates (outside this repository)

Because the CLI is **removed**, a pod deployed without these has no operational surface at all —
and because the identity header is an assertion (FR-035), a pod deployed without gates 2 and 3 has a
surface that is worse than none. 005 MUST NOT be deployed to STE before all five land:

1. **An internal ingress / APIM route for `/operations/**`** in the `cpp-aks-deploy` values, not
   exposed outside the estate.
2. **The gateway strips any client-supplied `CJSCPPUID` and injects the authenticated identity.**
   Without this, the authorisation is a caller's own claim about itself.
3. **An Istio `AuthorizationPolicy` and a `NetworkPolicy` restricting `/operations/**` to that
   gateway**, so no other workload in the mesh can reach it directly.
4. **usersgroups reachable from the pod** for the auth filter's identity client, with whatever
   network policy that requires.
5. **The Artemis audit connection** in the STE values — `CP_AUDIT_ENABLED=true` with the broker's
   hosts, port, credentials and TLS material from Key Vault — with `HTTP_AUDIT_ENABLED` and
   `AUTHZ_HTTP_ENABLED` left at their `true` defaults (FR-045). `CP_AUDIT_ENABLED` is part of this
   gate and not an optimisation: the service ships it `false` for the local loop, and the HTTP audit
   filter is built only where it is on, so a values file without it serves the operations API
   unaudited and condition (b) of Principle III is unmet. Start-up does not refuse any of that — the
   switches are an operator's choice to make, and what the pod does instead is say at WARN that it is
   publishing nothing — so this gate is checked by the deployment review and by the values
   themselves, not by the service refusing to start.

A sixth item is an **estate decision, not a deployment step**: `cp-audit-filter-springboot`
captures every request header verbatim, and its own README says a header allowlist "should be agreed
with the Audit team before rolling this out broadly". Raising that is a gate on rollout, recorded
here because this repository cannot close it.

## Assumptions

Answers taken during clarification, and the decisions taken under the two design reviews of
2026-09-19. Where something was genuinely open, the least behaviour-changing option was chosen;
where a review changed an earlier answer, the later one stands and the earlier is named so the
change is readable.

1. **The CLI's orchestration moves into application services, verbatim.** Three of the six command
   classes hold more than argument parsing and printing: `list-batches` reads two repositories and
   the store, `generate-register` releases, narrows, re-assembles and requests, and
   `report-exceptions` computes a window and selects sinks. A controller may not call a repository
   (design rules, "Persistence"), so that orchestration moves into application-layer services with
   its logic unchanged and the controller maps the result. This is the least behaviour-changing way
   to satisfy the layering rule; the alternative — a controller holding the reads — would have been
   a new architectural exception.
2. **`GET /operations/flag` answers `200` for all three readings, including `UNREADABLE`.** The
   command exited `2`, and an earlier draft mapped that to `503`. It is `200`: the endpoint answered
   the question it exists to answer, and the answer is "nobody can read the flag". A `503` makes the
   gateway read this service as down, may strip the body that says *why*, and would be a claim about
   this service rather than about the flag.
3. **The notification dispositions take three different statuses.** `SETTLED` → `200` with the
   tally. `ALREADY_NOTIFYING` → `409`: another notifier holds the claim and this call changed
   nothing, which is the definition of a refusal. `CLAIM_LOST` and `INCOMPLETE` → `500` with the
   disposition as the bounded reason: the call tried and could not finish, which is what the command
   meant by exit `2`. An earlier draft put all four unsettled cases on `409`; that conflated a call
   that never started with one that got half-way.
4. **`reason` values are the codes the commands already printed**, normalised to one spelling in the
   OpenAPI document rather than invented. Where a code is a gate's or a disposition's own
   (`FLAG_OFF`, `FLAG_UNREADABLE`, the four dispositions), it is passed through as that type spells
   it. The new codes are `SCHEDULE_RUNNING`, `FLAG_ON` (supersede's refusal) and `COMMAND_NOT_WIRED`
   — one per new rule.
5. **The request contract is closed.** An unknown field in a request body is refused, for the reason
   the inbound queue message's contract is closed (FR-028): tolerating drift hides it.
6. **The 18:00 lock is taken, not asked about.** An earlier draft had the endpoint check whether the
   lock was held and refuse `409 SCHEDULE_RUNNING` at request time. That is a check-then-act against
   a scheduler that can start in between. The background run acquires the same lock non-blockingly
   and records the refusal as its own outcome (FR-018).
7. **Regeneration is asynchronous.** The CLI requested renders inline under a sixty-minute deadline,
   which no ingress will hold a connection for. `202` with a run id, the work on the generation
   scheduler's single thread, the result read back from the run report line and `GET
   /operations/batches`. This is the largest departure from the command's shape, and it is forced:
   an inline endpoint gives the caller a `504` while the pod keeps working, and the retry that
   follows meets the live-key index and fails a run that had succeeded.
8. **Audit bodies are off, and bounded fields go on instead.** `audit.http.include-payload-body` is
   set **false** explicitly — the library's default is `true` and would put every response on the
   audit topic, including the batch listing's masked addresses and court-centre ids and every
   `ProblemDetail`. What the audit event needs is the action, the outcome, whether the flag was
   overridden and (for supersede) the count, and the generic filter can infer none of that from a
   body; those are supplied through the starter's own seam. An earlier draft left bodies on.
9. **Authorisation and audit are secure by default, and switchable by the operator.** *Decided by
   the design owner, 2026-09-20, replacing the start-up refusal an earlier draft of FR-045 and
   FR-053 described.* Both library switches default **off** when nothing sets them, which is the
   wrong way round for a service that serves operator endpoints, so `application.yaml` defaults both
   to `true`. That is where condition (a) of Principle III is met, and half of where (b) is: the
   HTTP audit filter is built only where the transport key `cp.audit.enabled` is on too, and that one
   ships `false` for the local loop, so (b) is met by a deployed values file setting
   `CP_AUDIT_ENABLED=true` beside it (FR-045, deployment gate 5). A pod with the filter on and the
   transport off comes up, serves, and says at WARN that nothing is being published. What was rejected is the rule on top of it:
   start-up refusing the combination of the operations API enabled with either filter off, on a
   `yotresultsdistribution.servicebus.namespace` discriminator. It made an operator's own configuration
   choice a reason for a pod not to come up, and it made a deployed environment and a laptop two
   different products. An operator who switches a filter off has said what they meant; the pod
   comes up, and the environment's configuration records what they did.
10. **Nothing about the exception report's window or sinks changes.** The endpoint computes the same
    window the command computed, from the same schedule, and asks the same sinks.
11. **Supersede is subordinated to the flag, bounded, and reversible-by-preview.** *Confirmed by the
    design owner, 2026-09-19 — every protection below is decided, not proposed.* Its command read
    the flag nowhere, and the endpoint reads it: an unconditional HTTP mutation that makes this
    service give up a period of registers is a second lever however well authorised. Admitted only
    while an uncached read says OFF; `409 FLAG_ON` when it is on; `409 FLAG_UNREADABLE` fail-closed;
    no override, ever. Plus a `dryRun` that answers the count and changes nothing, a refusal for an
    instant in the future, an age bound (`yotresultsdistribution.operations.supersede-max-age`, default
    30 days) so that one keystroke cannot give up the estate's whole history of registers, and the
    count carried into the audit event. This is why constitution condition (c) reads "at least as
    strictly as its command was".
12. **`ignoreFlag` is per request, for one batch, and nothing else.** *Decided by the design owner,
    2026-09-19.* Never a configuration default, never a deployment value, always audited with the
    caller's identity, always `reason=overridden` on the run line, and **only with an explicit
    `batchId`** — a whole date under override is refused `400 OVERRIDE_REQUIRES_BATCH` (FR-017).
    This is narrower than `--ignore-flag` was: the command would override a whole register date, and
    an endpoint reaches further than an exec did. A date-wide regeneration with the flag ON is
    unaffected. No other endpoint has an override at all.
13. **A pod without the generation half answers `501 COMMAND_NOT_WIRED`** on the three endpoints
    that need its beans, which is exactly what the CLI answered on such a pod. A `404` would read as
    a mistyped URL and a `500` would be a bean-definition error reaching an operator.
14. **What the separate CLI JVM gave up is stated rather than assumed.** (a) The rule that a CLI JVM
    must not subscribe to `public.event` is **retired** with the JVM it was about — an operations
    call is served by a pod that is already a consumer; the rule and its checks go. (b) The
    population that can regenerate or supersede widens from holders of cluster RBAC on the namespace
    to an estate-wide usersgroups group; that is a deliberate trade for naming the caller and
    auditing the call, and it is listed as an open question below. (c) The e-mail sole-committer
    guarantee is **not** lost, because it never came from the JVM: `RegisterNotifierService`'s
    claim and lease already arbitrate concurrent notifies.
15. **The identity header is a gateway assertion.** `cp-auth-rules-filter` authorises whatever
    `CJSCPPUID` it is given; the mesh-level controls that make that safe are deployment gates 2 and
    3, and the spec states them because the endpoints are unsafe without them.
16. **No `doc/DEFECT-FIXES.md` row.** Stated in Context and repeated here because it is the rule a
    task is most likely to break: replacing this service's own operational surface is not a
    deviation from a legacy oracle.
17. **The five removal facts are one phase, landing last.** Nothing is deleted until every endpoint
    that replaces it has a passing test (FR-051).
18. **From Phase 2 onwards a task's tests land in the same commit as its code, and no red run is
    recorded.** *Design owner, 2026-09-20.* The commit order inside a pair is not audited either.
    This is a **Principle II waiver**. It waives the recording and nothing else, and it expires
    with this increment. It lives here and in this increment's tasks file only: the constitution
    stands at 5.0.3 and states the convention absolutely, so the `qa` gate reads the convention and
    not the waiver. `constitution-amendment-proposal.md`, alongside this spec, is the unratified
    proposal to write the waiver into Principle II; until the design owner rules on it, this
    assumption is the whole of the waiver.
    What the reviewers judge in place of the ceremony is the coverage gate (LINE 0.88 /
    BRANCH 0.85, `config/**` excluded) and behaviour coverage **per endpoint** — allow and deny per
    group, every refusal code the endpoint can answer, and the flag rule where the endpoint has
    one. This supersedes, for this increment only, the 003-era condition that a third
    untested-first configuration would be reverted. Every task still gets its test; only the
    recording of a red run goes, and no other gate is relaxed by it.

## Decided by the design owner (2026-09-19)

Three questions this spec raised are answered, and are requirements rather than assumptions:

- **The caller group is "Second Line Support", final.** Every drools rule allows exactly that one
  group and no other. It is an **estate-wide** group — used in 57 ACL files across CPP — so the
  population that can regenerate and supersede is wider than the holders of cluster RBAC on this
  namespace who could run the commands. That is the deliberate trade for a named caller and an audit
  event. Narrowing it later to a court-register-specific group is a change to
  `src/main/resources/acl/operations-rules.drl` and nothing else: no code, no contract, no
  redeploy of anything but this service.
- **`ignoreFlag` requires an explicit `batchId`** (FR-017, assumption 12).
- **Every supersede protection is confirmed** (FR-021, assumption 11): flag OFF only, fail-closed on
  unreadable, no override, `dryRun`, no future instant, the 30-day age bound, and the count in the
  audit event.

## Open

- **The audit library's swallowed publishing failure.** `cp-audit-filter-springboot`'s
  `AuditService.postMessageToArtemis` catches every `Exception`, logs it and returns, so without
  intervention an operations call could succeed with no audit event — which Principle III(b) and
  Principle VI both refuse. The starter registers that bean `@ConditionalOnMissingBean`, so this
  service supplies its own: the **request** event is published before the action and a failure
  refuses the call `503 AUDIT_UNAVAILABLE`, and the **response** event is published after it, where
  a failure can only be logged at ERROR and counted. That last case is a recorded shortfall, not a
  closed one — it is the single entry in the plan's Complexity Tracking, the durable outbox that
  would close it is deliberately out of this increment, and **the entry needs the design owner's
  dated sign-off before the audit tasks (T044/T045) land**.
- **The audit header allowlist.** `cp-audit-filter-springboot` captures every request header
  verbatim, including `Authorization` and `Cookie`, and its own README says an allowlist "should be
  agreed with the Audit team before rolling this out broadly". It is an estate decision this
  repository cannot take, it is a **rollout gate** rather than a build gate, and nothing local works
  around it.
