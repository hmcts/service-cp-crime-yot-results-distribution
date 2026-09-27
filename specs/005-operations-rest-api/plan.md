# Implementation Plan: Operations REST API, replacing the operations CLI

**Branch**: `005-operations-rest-api` | **Date**: 2026-09-19 | **Spec**: [spec.md](./spec.md)
**Input**: `specs/005-operations-rest-api/spec.md`, with `research.md`, `data-model.md` and
`quickstart.md` alongside it.

## Summary

Seven endpoints under `/operations/**` replace the six operations commands, and the CLI is removed.
The HTTP layer is an inbound adapter in `uk.gov.hmcts.cp.yotresultsdistribution.api`: it parses, calls one
application service, and maps the answer. Where a command class held orchestration — `list-batches`,
`generate-register`, `report-exceptions` — that orchestration moves into an application service
**unchanged**, because a controller may not call a repository.

Two estate starters do the work the CLI could not: `cp-auth-rules-filter` 1.0.7 (drools rules over
the caller's usersgroups membership, identity from `CJSCPPUID`) and `cp-audit-filter-springboot`
1.0.5 (every request and response published to the audit context). Both have traps that are
load-bearing rather than incidental, and `research.md` records each with its source; the three that
would stop the service starting are R6 (`cp.audit.enabled` is on by default and its connection
settings are validated even when HTTP audit is off), R9 (the audit library's stereotypes are
component-scanned because this service's `@SpringBootApplication` sits at `uk.gov.hmcts.cp`), and
R8 (the audit library's `@Primary` JMS beans would hijack the `public.event` listener's connection
factory).

Three endpoints are **stricter** than the commands they replace, and each difference is forced by
the surface rather than chosen: the regeneration is asynchronous and acquires the nightly ShedLock;
supersede is admitted only while the flag says OFF, with a dry run and an age bound and no override;
and every endpoint is refused for anyone outside "Second Line Support". Everything else takes the
same arguments, refuses the same refusals and answers the same fields.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1
**Primary Dependencies (new)**: `uk.gov.hmcts.cp:cp-auth-rules-filter:1.0.7`,
`uk.gov.hmcts.cp:cp-audit-filter-springboot:1.0.5`, `spring-boot-starter-web` (confirm whether it is
already a first-class dependency at the first build task; actuator alone is not enough to map a
controller)
**Storage**: unchanged — the processed log, the register store, the `shedlock` table, the
file-service datasource. **No migration in this increment.**
**Testing**: JUnit Jupiter 6 + Mockito + AssertJ; `@WebMvcTest` slices per controller; WireMock for
the usersgroups identity service; Testcontainers Postgres for the lock and store `*IT`s; a drools
unit suite with no Spring; `ApplicationContextRunner` for the start-up refusals
**Target Platform**: AKS, Flux, `springboot-app` chart; port 4550 in Kubernetes, 8082 locally
**Project Type**: message-driven service with a scheduled leg and, from this increment, an
operations HTTP surface
**Constraints**: responses in bounded codes, counts and identifiers only; no caller input echoed; no
business endpoint; coverage ratchet unchanged (LINE ≥ 0.88, BRANCH ≥ 0.85)
**Scale/Scope**: seven endpoints, one action filter, one error-attributes bean, one audit bean,
three new application services, one OpenAPI document, one drools file; ~20 production classes added
and ~20 removed

### Configuration (this increment)

| Key | Default | Why |
|---|---|---|
| `yotresultsdistribution.operations.enabled` | `true` | Deployment shape: whether the endpoints are served. **Not** a cutover lever |
| `yotresultsdistribution.operations.supersede-max-age` | `30d` | The oldest `sharedBefore` supersede will accept; an unbounded irreversible mutation is one keystroke from the estate's whole history |
| `yotresultsdistribution.operations.lock-wait` | `0s` | How long the background regeneration waits for the register-generation lock before recording that it could not take it. Zero is a non-blocking attempt |
| `authz.http.enabled` | `${AUTHZ_ENABLED:true}` | The library default is **false**; the filter does not exist unless this is explicitly true |
| `authz.http.identity-url-template` | `${CP_BASE_URL}/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions` | usersgroups; the caller id travels in the header, not the path |
| `authz.http.user-id-header` | `CJSCPPUID` | |
| `authz.http.action-header` | `CPP-ACTION` | Written by our own filter and overridden server-side |
| `authz.http.accept-header` | `application/vnd.usersgroups.get-logged-in-user-permissions+json` | |
| `authz.http.drools-classpath-pattern` | `classpath:/acl/**/*.drl` | |
| `authz.http.reload-on-each-request` | `${AUTHZ_RELOAD_RULES:false}` | The library default is **true** |
| `authz.http.action-required` | `false` | Our filter always supplies one for our paths |
| `authz.http.deny-when-no-rules` | `true` | Default-deny, and it also governs "the DRL failed verification" |
| `authz.http.exclude-path-prefixes` | `/actuator`, `/error` | Setting this **replaces** the library list; omitting `/actuator` makes the probes answer 401 |
| `authz.http.enabled` | `${AUTHZ_HTTP_ENABLED:true}` | **On by default** — the library's own condition defaults off, and this reverses it. No refusal behind it: an operator may switch it off and the pod starts (FR-045) |
| `audit.http.enabled` | `${HTTP_AUDIT_ENABLED:true}` | **On by default**, on the same terms as `authz.http.enabled`, and half of condition (b) — the other half is `cp.audit.enabled` below, without which this switch builds no filter |
| `audit.http.openapi-rest-spec` | `yot-results-distribution-openapi.yaml` | A **suffix** glob over the whole classpath, not a path: uniquely scoped so exactly one resource matches, proven by a real-classpath test (T043). Unset where both audit switches are on, start-up refuses |
| `audit.http.include-payload-body` | `false` | Explicit. The library default is `true` and would publish every response body |
| `cp.audit.enabled` | `${CP_AUDIT_ENABLED:false}` — `false` unless the environment sets it, and every deployed values file MUST (deployment gate 5) | The library's own switch, and **the second half of condition (b)**: every `audit.http.*` bean sits inside the `@AutoConfiguration` class it gates, so the row above builds no filter without it. Shipped off because the transport's connection factory validates `cp.audit.hosts`/`port` while it is constructed and a laptop has no broker; the compose and test profiles set it `false` explicitly for the same reason. A pod with the filter on over it says so at WARN (FR-045) |
| `cp.audit.hosts` / `port` / `user` / `password` / `ssl-*` | per environment, from Key Vault via CSI | No secret in a committed value |
| `yotresultsdistribution.cli` | **removed** | — |

### Constitution Check

| Principle | How this increment satisfies it |
|---|---|
| I — defect-fix-first | Not engaged. There is no legacy oracle for an operational surface; **no `doc/DEFECT-FIXES.md` row is added, amended or flipped**, and `RegisteredDefectFixes` and `DifferentialAuditTest` must stay green untouched |
| II — TDD | Every task below is a red/green pair, a `[A]` characterisation, or a documentation task. Red runs are failing assertions, never compile errors; the seams land in the test task |
| III — message-contract first | The reason for the amendment (**5.0.0**). All four conditions are requirements (FR-005–FR-009, FR-010–FR-013, FR-024–FR-027) and gate 8 of `workflow.md`. `openapi.yaml` joins the owned contracts with a contract test in both directions. Conditions (a) and (b) are carried by the **defaults**: `authz.http.enabled` and `audit.http.enabled` read `true` in `application.yaml` against two libraries whose own conditions default off, and (b) is completed by `CP_AUDIT_ENABLED=true` in the deployed values, without which the audit filter is never built (FR-045, constitution 5.0.1, deployment gate 5). There is no start-up refusal on the combination and no environment discriminator — the pod always comes up — and therefore no exemption to record; what is still refused is a **value** that cannot mean what it says (FR-053) |
| IV — canonical JSON in, typed out | Unaffected: the hearing payload does not come near this surface. The API's own requests and responses are typed records, as everything this service *produces* is |
| V — ports and adapters | The controllers are inbound adapters. No controller holds a repository, an HTTP client or a decision; the three command classes that held orchestration give it to application services |
| VI — nothing swallowed | Every refusal is an explicit status with a bounded reason. The one place this is at risk is the audit starter, whose `AuditService.postMessageToArtemis` catches every `Exception`, logs it and returns — so an operations call could succeed with no audit event, which condition (b) of Principle III also forbids. **Not accepted as the library's behaviour**: the starter registers that bean `@ConditionalOnMissingBean` (research R10), so T045 supplies `api/OperationsAuditService` in its place and it does not swallow. What remains after that is one case and is in Complexity Tracking below |
| VII — privacy in telemetry | FR-025, FR-026, FR-038, FR-046. Audit bodies off; the `CJSCPPUID` out of every log line; the privacy sweep extended to controller responses and `ProblemDetail` |
| VIII — estate conventions | Gradle, the pinned analysis set, Conventional Commits, no AI attribution, package root `uk.gov.hmcts.cp.yotresultsdistribution` |

One entry in Complexity Tracking, below — a residual of the audit library that Principle VI and
Principle III(b) both reach. Nothing else here asks for an exception to a principle: the amendment
**is** the exception, taken at the constitution rather than in a plan.

## Project Structure

### Documentation (this feature)

```
specs/005-operations-rest-api/
├── spec.md          the requirements, the amendment proposal, the gates and the open questions
├── plan.md          this file
├── research.md      the starters' real behaviour, with sources — read before writing a filter
├── data-model.md    the seven endpoints: request, success shape, every refusal
├── quickstart.md    the curl walkthrough that replaces the CLI one
└── tasks.md         the phases
```

### Source code — additions and changes

```
src/main/java/uk/gov/hmcts/cp/
├── Application.java                          CHANGED  the component-scan exclude filter (R9)
└── yotresultsdistribution/
    ├── api/                                  NEW
    │   ├── OperationsActionFilter.java               path+method → action name, server-derived,
    │   │                                             overrides the caller's header (R2)
    │   ├── ActionRequestWrapper.java                 the header override
    │   ├── OperationsContentTypeFilter.java          the `multipart/` guard, at +40: inside the
    │   │                                             authorisation filter, outside the audit one
    │   ├── OperationsRefusalWriter.java              the shape a refusal taken in a filter is
    │   │                                             written in, shared by the two
    │   ├── OperationsExceptionHandler.java           @RestControllerAdvice → ProblemDetail
    │   ├── OperationsErrorAttributes.java            the /error body for 401/403 and unmapped
    │   │                                             paths: bounded, no path, no trace (R5)
    │   ├── OperationsAuditFacts.java                 request-scoped bounded facts
    │   ├── OperationsAuditService.java               the starter's AuditService seam (R10)
    │   ├── FlagController.java
    │   ├── BatchesController.java                    list, generate, notify
    │   ├── RegistersController.java                  recorded-while-off, supersede
    │   ├── ExceptionReportsController.java
    │   ├── NotWiredController.java                   501 COMMAND_NOT_WIRED when the generation
    │   │                                             half is off (FR-052)
    │   └── dto/                                      the request and response records
    ├── application/                          NEW classes only
    │   ├── BatchListingService.java                  ListBatchesCli's reads, unchanged
    │   ├── RegisterRegenerationService.java          GenerateRegisterCli's orchestration, unchanged
    │   ├── OperationsRunLauncher.java                the 202 hand-off: mint a run id, submit to the
    │   │                                             generation executor, take the lock (R12, R16)
    │   └── OnDemandExceptionReportService.java       ReportExceptionsCli's window and sinks
    ├── config/
    │   ├── OperationsProperties.java         NEW     yotresultsdistribution.operations.*
    │   ├── OperationsWebConfig.java          NEW     filter registration, conditional controllers
    │   ├── PropertiesValidator.java          CHANGED the audit transport's and the operations
    │   │                                             API's value refusals (FR-053); no cross-field
    │   │                                             rule and no environment discriminator
    │   ├── PublicEventsConfig.java           CHANGED the connection factory taken by name (R8)
    │   └── CliModeConfig.java                DELETED
    └── batch/cli/                            DELETED (10 classes)

src/main/resources/
├── openapi.yaml                              NEW  the third owned contract
├── acl/operations-rules.drl                  NEW  seven allow rules, "Second Line Support" only
├── application.yaml                          CHANGED  the authz/audit/operations blocks; the
│                                                      yotresultsdistribution.cli key removed
└── logback-cli.xml                           DELETED

docker/startup.sh                             CHANGED  the command dispatch removed
scripts/container-smoke.sh                    CHANGED  an endpoint instead of two commands
build.gradle, gradle/libs.versions.toml       CHANGED  the two starters
README.md                                     CHANGED  the operations section (done in d73ef50)
specs/002-consolidate-progression-leg/quickstart.md   CHANGED  curl instead of CLI examples
```

**Structure decision**: the REST layer is a peer of `inbound/` — a second inbound adapter — and not
a layer above the application. Nothing in `api/` is imported by anything outside it.

### Port and service contracts (this increment)

Three new application services, each taking a command class's body unchanged and returning a typed
record instead of printing lines:

| Service | From | Returns |
|---|---|---|
| `BatchListingService.batchesOn(LocalDate)` | `ListBatchesCli.listDate` | `List<BatchListing>` — batch id, court house, state, record count, recipients (masked address + status) |
| `BatchListingService.recordedWhileOff()` | `ListBatchesCli.listRecordedWhileOff` | `List<RecordedWhileOff>` — record id, hearing id, register date, flag state |
| `RegisterRegenerationService.regenerate(Selection, RunCorrelation)` | `GenerateRegisterCli.generate` | `RegenerationTally` — released, registers, batches, requested, deferred, per-batch state, withheld reasons |
| `OperationsRunLauncher.launch(Selection, Caller)` | new (the 202 hand-off) | `RunAccepted` — the run id |
| `OnDemandExceptionReportService.report(Window, boolean email)` | `ReportExceptionsCli.asked/reported` | `OnDemandReport` — the report, the per-sink outcomes, the run id, the duration |

`GateDecision`, `NotificationSummary`, `NotificationDisposition`, `FlagDecision`, `ExceptionReport`
and `RegisterRecord` are reused as they are. No existing port's signature changes.

### Test matrix

| Suite | Kind | Covers |
|---|---|---|
| `api/OperationsRulesTest` | drools, no Spring | one allow case and one deny case per action; an unknown action denied; the file names no group but "Second Line Support" |
| `api/OperationsActionFilterTest` | plain servlet mocks | path+method → action for all seven; a caller-supplied `CPP-ACTION` overridden; an unrecognised path untouched |
| `api/OperationsContentTypeFilterTest` | plain servlet mocks | a `multipart/` body refused `415` on every endpoint, however spelled; the JSON they take passed through; a path that is not ours untouched |
| `api/FlagControllerTest` | `@WebMvcTest` | ON, OFF, UNREADABLE all `200`; the reason is the flag's bounded code |
| `api/BatchesControllerTest` | `@WebMvcTest` | the listing shape and masking; generate's `202` + run id; generate's `409 FLAG_OFF` / `FLAG_UNREADABLE`; **`400 OVERRIDE_REQUIRES_BATCH` for `ignoreFlag` without a `batchId`**, and the accepted override *with* one; notify's `200` / `409 ALREADY_NOTIFYING` / `500` / `404` / `503` |
| `api/RegistersControllerTest` | `@WebMvcTest` | recorded-while-off; supersede `200`, `dryRun`, `409 FLAG_ON`, `409 FLAG_UNREADABLE`, `400` future, `400` older than the bound, `400` absent |
| `api/ExceptionReportsControllerTest` | `@WebMvcTest` | the report shape; `409 EMAIL_OUTPUT_DISABLED` / `EMAIL_OUTPUT_NOT_WIRED`; the flag read nowhere (a strict mock on the reader) |
| `api/OperationsExceptionHandlerTest` | `@WebMvcTest` | every refusal is a `ProblemDetail` with a bounded reason and **no** supplied value; a malformed body; an unknown field |
| `api/OperationsErrorAttributesTest` | slice | the `/error` body for 401/403 and an unmapped path: no `path`, no `trace`, no message |
| `api/OperationsAuditFactsTest` | unit | action, outcome, flagOverride and supersede count reach the payload; no body does |
| `api/NotWiredControllerTest` | `@WebMvcTest` | `501 COMMAND_NOT_WIRED` on a generation-off pod for the three endpoints that need those beans |
| `api/OpenApiContractTest` | slice | every mapped path/method is in `openapi.yaml` and every path in it is mapped; `{batchId}` declared as a path parameter |
| `application/BatchListingServiceTest` | Mockito | the reads, the ordering, the masking rule, the store-failure path |
| `application/RegisterRegenerationServiceTest` | Mockito | the CLI's cases, re-pointed: narrowing, withholding (`key-in-flight`, `outside-the-bound`), the tally, the deadline |
| `application/OperationsRunLauncherTest` | Mockito | the lock is **taken**; a lock it cannot take records the refusal and does nothing; the run id is minted before the submit |
| `application/OnDemandExceptionReportServiceTest` | Mockito | the window forms (instant, ISO duration, `<n>d/h/m/s`), the e-mail refusals, the sink selection, "not every sink took it" |
| `config/OperationsPropertiesTest` | `ApplicationContextRunner` | the defaults and the overrides |
| `config/ConfigurationValidationTest.OperationsSettings` | `ApplicationContextRunner` | where the value refusals live, beside every other startup rule: a transport switched on that names no broker, a blank host, or a port outside 1..65535; both audit switches on with no spec key; a non-positive `supersede-max-age`; a negative lock wait — each with its "should start" counterpart, including both ends of the port range. Plus the three cases that pin what is **not** refused: both filter switches off, audit off, authorisation off. `ShippedConfiguration` pins the two `true` defaults that carry conditions (a) and (b) |
| `config/AuditComponentScanTest` | `ApplicationContextRunner` over `Application` (test profile) | the exclusion of `uk.gov.hmcts.cp.filter.audit` holds — without it the context does not start (R9) |
| `config/PublicEventsFactoryTest` | context | the listener container's connection factory is the public-event one, not the audit one (R8) |
| `api/OperationsAuthzIT` | full context + WireMock | **the real filter**: in the group → served; not in the group → 403; no `CJSCPPUID` → 401; identity service 500 → 403; a forged `CPP-ACTION` → still refused; `/actuator/health` → 200 |
| `api/OperationsAuditIT` | full context, publisher seam | an audit event per request and per response; it carries the action, the outcome and the caller; it carries **no** request or response body |
| `e2e/OperationsConcurrencyIT` | Testcontainers | two notifies for one batch; a regeneration racing the scheduled run in both orders; two regenerations for one date; a public event arriving during a regeneration |
| `config/TelemetryPrivacyTest` | extended | controller responses and `ProblemDetail` bodies join the log-statement sweep; the `CJSCPPUID` appears in no log statement |
| `e2e/ContainerSmokeIT` / `scripts/container-smoke.sh` | `[A]` | the image starts the application with no arguments and answers an endpoint |

## Coordination contract with the concurrent 004 tree

004 (`release-stale-batches`) is built at the same time in the **main checkout**,
`/home/sachin/moj/service-cp-crime-yot-results-distribution`. This branch never touches that tree, and the
file ownership is:

**005 owns** — `batch/cli/*`, `config/CliModeConfig`, `docker/startup.sh`, the new `api/` package,
the new `application/` services listed above, `src/main/resources/openapi.yaml`,
`src/main/resources/acl/`, `build.gradle`, `gradle/libs.versions.toml`, the `authz.*`, `audit.*`,
`cp.audit.*` and `yotresultsdistribution.operations.*` blocks of `application.yaml` and the deletion of
`yotresultsdistribution.cli`, `Application.java`,
`.specify/memory/constitution.md`, `CLAUDE.md`, `.claude/rules/*`, `.claude/agents/*`, README's
operations section, `specs/002-consolidate-progression-leg/quickstart.md`'s CLI examples,
`scripts/container-smoke.sh`, `logback-cli.xml`.

**005 must NOT touch** — `batch/RegisterGenerationJob`, `batch/GenerationReconciler` **or the
releaser and sweep that replace it**, `application/DocumentRenderer`, `adapter/systemdocgenerator/*`,
`adapter/stub/StubDocumentRenderer`, `domain/DocumentStatus`, `domain/BatchFailureReason`,
`domain/CompletedBy`, `config/GenerationProperties`, `config/GenerationMetrics`,
`config/SchedulingConfig`, `config/SchedulingInfrastructureConfig`, `config/BatchSweepConfig`
(004's, new), `config/IntakeSweepConfig`, `config/ProcessedLogConfig`, `db/migration/V6*` and
`V7*`, the `yotresultsdistribution.generation.*` and `yotresultsdistribution.report.*` blocks of
`application.yaml`, README's generation section, or `design_rules.md`'s flow diagram and batch
state machine. Those are 004's.

**The five `config/*` files in that list are the ones a file-level ledger loses**, because 005 has
to edit them *eventually*: every one of them carries a `CliModeConfig` conjunct that T054 deletes,
and 004 is wiring its releaser and its batch-age sweep off the same conjunct. **They are 004's
until 004 merges to `main` and 005 has rebased onto it**, and T054 is worded to run only after that
and to re-derive its own enumeration against the rebased tree. 005 taking one early deletes a bean
from under an unmerged branch and loses both edits.

**Shared, by agreement**:

- `config/PropertiesValidator` — 004 renames the generation grace period; 005 adds the audit
  transport's and the operations API's **value** refusals. Different methods, one file: expect a
  textual conflict on the rebase and resolve it by keeping both. 005's edit is **not** confined to
  one added method, and the rebase should know it: the constructor gains a parameter, the class
  gains two fields, a pattern and a range constant and seven private helpers, `afterPropertiesSet`
  gains a call and `@EnableConfigurationProperties` an entry. What the agreement was protecting is
  intact — the static `validate(...)` is byte-identical to the base — so the resolution is to keep
  both sides of the constructor and annotation hunks rather than to take either whole.
- `.claude/rules/design_rules.md` — 004 edits the flow diagram and the batch state machine; 005
  edits the opening paragraph, the package structure, the Cutover Rule's wording about endpoints,
  the topic section's retired CLI-JVM rule, the out-of-scope list and the new "The operations API"
  section.
- `config/PublicEventsConfig` — **both**, and the earlier draft of this contract had it as 005's
  alone (T001's analysis). 005 takes the connection factory by name (R8); 004 removes
  `yotresultsdistribution.generation.completion`, and with it the `setAutoStartup` conjunct and the CLI-JVM
  javadoc. Two separate edits to one file: expect a textual conflict on the rebase and resolve it by
  keeping both.
- `domain/RunReport` — **both**, and it is `domain/`, not `batch/`: 004 replaces `reconciled` with
  `releasedBatches`/`releasedRegisters` and 005 adds the operator trigger. Its line is asserted in
  `batch/RegisterGenerationJobTest`, which 004 rewrites and 005 must not touch, and the only class
  that constructs it is `batch/RegisterGenerationJob`, which is 004's. **005 adds the trigger
  through a factory that leaves the existing call site as it is**, or the task goes back to the
  orchestrator; it does not edit the job.
- `domain/RunReportTest` — **both, and it exists in neither tree today**, which is how a
  file-level ledger loses a file: 004's T017 names it "(extend)" and 005's T036 names it "(new)",
  so whichever lands second either conflicts with the other's suite or replaces it. **Creation is
  004's**: its assertions are about the line's own format string, which is what 004 rewrites.
  005's T036 therefore **extends** the rebased suite rather than creating it, and if 005 reaches
  T036 before 004 has landed, it creates the file and 004's assertions are merged into it on the
  rebase. Either way both assertion sets survive: `the_run_line_carries_both_released_numbers`,
  `a_run_that_released_nothing_says_zero` and `the_run_line_carries_no_reconciled_anywhere` are
  004's and are kept whole, and the trigger cases are added beside them. A whole-line assertion
  from either side is re-read against the merged line rather than deleted.
- `config/ConfigurationValidationTest` — **both**, and it was missing from the first draft of this
  ledger. 004 extends it with `stale-after`, `batch-age-refresh` and `batch-generated-within` and
  removes the `completion` cases; 005 adds a nested `OperationsSettings` class and two cases to
  `ShippedConfiguration`. **The base runner is no longer a conflict**: gate round 1 added five
  library keys to it for a refusal that gate round 3 withdrew, and the list is back to the five
  identity and endpoint properties 004 will see. Additive on both sides.
- `src/main/resources/application.yaml` — **both**. 004 touches four sites in the
  `yotresultsdistribution.generation.*` block (the rename and its comments); 005 adds the `cp.audit.*` key
  and the `authz.http.enabled` / `audit.http.enabled` defaults, will add the rest of the `authz.*`,
  `audit.http.*` and `yotresultsdistribution.operations.*` blocks, and deletes `yotresultsdistribution.cli` —
  **after the rebase**, because the conditionals that read it are 004's until then. Different
  blocks of one file.
- `config/CliModeConfig` and `config/CliModeConfigTest` — 004 **edits** them (the releaser and the
  sweep are wired off a command JVM); 005 **deletes** them with the CLI. The rebase resolution is
  the deletion, and 004's edit is discarded *with the file* — but only after the check below.
- `application/ExceptionReportService`, `batch/cli/ReportExceptionsCliTest` and
  `application/ExceptionReportServiceTest` — **004's semantic handoff to 005, and the one a
  file-level ledger hides.** 004's FR-019 adds a report kind: a batch failed under its new
  stale-release reason is reported as `BATCH_RELEASED` rather than `BATCH_FAILED`, in the CSV, the
  table and the log event alike. 005 ports `report-exceptions` to an endpoint and deletes the CLI
  class and its test. **The port must carry 004's behaviour**: before `batch/cli/` is deleted
  (Phase 7), the endpoint's tests must assert the `BATCH_RELEASED` kind that
  `ReportExceptionsCliTest` asserted, or 004's new behaviour is deleted along with the class that
  proved it. Deleting a test is not the same as replacing it.
- `config/TelemetryPrivacyTest` — **both**. 004 puts its releaser and sweep on the `GenerationLegs`
  drive; 005 puts the controllers and the `ProblemDetail` bodies on the sweep. Additive on both
  sides.
- `README.md`, `specs/002-consolidate-progression-leg/quickstart.md` and
  `specs/003-exception-report/quickstart.md` — **both** (004's FR-016 documentation list). 004
  rewrites the reconciler sentences; 005 rewrites the CLI examples. Different paragraphs.
- `.claude/agents/{spec-validator,software-engineer,qa,code-reviewer}.md` and
  `.claude/rules/design_rules.md` — **both**, for the same reason and with the same resolution: 004
  replaces the reconciler wording, 005 replaces the CLI wording.
- `config/GenerationProperties` — 004's alone, but it **breaks two of 005's test files on the
  rebase**: it deletes `completion` and its two constants, and `config/PublicEventsFactoryTest`
  (005's, new) sets `yotresultsdistribution.generation.completion=event` while the pre-existing
  `config/PublicEventsConfigTest` constructs `GenerationProperties` positionally with
  `COMPLETION_EVENT`. One line each, test-only, and expected.

- **004 owes 005 one behaviour** — and **discharges it**: its pre-batching pass must not fail an
  operator-initiated batch that spans 18:00. 004's FR-017 states it as "the longer of the minimum
  age and the nightly run's own lock duration", which is at least the run deadline plus the margin
  `PropertiesValidator` already enforces between them, and its data model keys the choice off the
  `system_generated` column `V2` already carries and the CLI's assembly already writes `false` into.
  **It is 004's change**, 005 does not make it, and 005's regeneration service keeps writing
  `system_generated = false` because it is the CLI's body moved unchanged.

**Merge order**: 004 → `main`, then 005 rebases onto `main`, re-runs its spec-validator, then
005 → `main`. One committer per tree, always.

## Risks

1. **The two starters' three start-up traps** (research R6, R7, R9). Each is a context that will not
   start, and two of them do not name themselves usefully. Mitigated by making them tasks with
   tests: a context test for the component-scan exclusion, `ApplicationContextRunner` cases for the
   audit settings, and the OpenAPI file matching the setting that names it.
2. **The `@Primary` JMS hijack** (R8). Worst case: the public-event listener silently attaches to
   the audit broker and every document outcome is lost. Mitigated by a context test naming the
   factory, and by taking the connection factory by name.
3. **The audit starter swallows its own failures.** `AuditService.postMessageToArtemis` catches
   every `Exception`, logs it and returns (verified in the 1.0.5 bytecode), so a call that cannot be
   audited would proceed and say so only in a log line. Two things answer it: the audit path is
   *engaged by default* (FR-045) and a transport switched on has to name somewhere it could reach
   (FR-053), and **T045 replaces the bean** — the starter registers it `@ConditionalOnMissingBean`
   — with one that refuses the call (`503 AUDIT_UNAVAILABLE`) when the request event cannot be
   published, before the action runs.
   The residual is the **response** event, which is published after work that cannot be undone; it
   is logged at ERROR and counted, it is the single entry in Complexity Tracking, and it is not
   hidden behind a claim we cannot make.
4. **Widened reach.** The population that can regenerate and supersede goes from holders of cluster
   RBAC on this namespace to the estate-wide "Second Line Support" group — decided by the design
   owner, and traded deliberately for a named caller and an audit trail. Bounded by the supersede
   age limit, the flag subordination, and `ignoreFlag` needing an explicit batch. Narrowing it later
   is a change to `acl/operations-rules.drl` alone.
5. **The rebase onto 004.** `PropertiesValidator`, `application.yaml` and `design_rules.md` are all
   touched by both. Small, and named above so neither side is surprised.
6. **Removing the CLI is irreversible in the image.** Which is why it is a phase of its own, last,
   after every endpoint has a passing test (FR-051), and why the deployment gates are stated in the
   spec rather than discovered at cutover.

## Complexity Tracking

One entry. The principle that had to *change* was changed at the constitution, under its own
amendment procedure; this is the one place a principle is not fully met and the shortfall is
recorded rather than argued away.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **Principle VI (nothing swallowed) and Principle III(b) (every endpoint audited), for the audit **response** event alone.** `cp-audit-filter-springboot`'s `AuditService.postMessageToArtemis` catches every `Exception`, logs it and returns — verified in the 1.0.5 bytecode, not inferred from the README — so a broker outage would let an operations call succeed while publishing nothing. T045 replaces that bean (the starter registers it `@ConditionalOnMissingBean`) with one that does not swallow, and publishes the **request** event before the action so a failure there refuses the call `503 AUDIT_UNAVAILABLE`. What is left is the **response** event: the action has already happened, so a publish failure there cannot be refused. It is logged at ERROR with the action and the run id and moves a bounded counter — it is not silent, but the audit trail for that one call is incomplete, and no status code can say so to a caller whose work is done. | Condition (b) is a requirement on an endpoint being *reachable* unaudited, which the `true` default (FR-045) and the fail-closed request event together close — the default is what makes an unaudited deployment a thing somebody had to do on purpose, and the request event is what makes an unpublished call a refused one. The response event records the outcome of work already done; making it a precondition of that work is not possible, and making the work conditional on it would mean a broker outage stopping every operator action during exactly the incident an operator is trying to end. | **A durable outbox** — write the response event into this service's own store and publish it from a sweep — would close it completely, and is rejected **for this increment** on scope: it is a table, a migration, a publisher and a retry policy for one event per operator call, on a surface that carries no defendant detail and whose actions are already recorded in `processed_request`, `register_batch` and the run report. **Retrying inline** was rejected because it holds the caller's connection open on the one path where the answer is already known. The outbox stays the named way to close this, and it is a story of its own, not a task smuggled into this one. **This entry needs the design owner's dated sign-off before Phase 8 lands** (T044/T045); until then the shortfall is recorded, not approved. **Status 2026-09-21:** Phase 8 has landed (`ae111962`) without it — nobody was available to give one during the run, and none was invented; the shortfall stands exactly as described. It is **outstanding and owed before the increment gate closes**, and if it is refused the durable outbox becomes a task of this increment rather than a story of its own. Recorded here so the gate cannot close over it; also carried in the T045 note in `tasks.md`. |
