# service-cp-crime-yot-results-distribution

Consumes hearing-resulted messages from a dedicated Azure Service Bus queue, builds one
youth-defendant court register document per hearing and **records** it in the service's own store;
a service-owned job at 18:00 Europe/London (Mon–Fri) batches the recorded documents per
(court centre, register date), renders each batch to PDF through **systemdocgenerator**
(`OEE_Layout5`, unchanged), learns the outcome from systemdocgenerator's public events on the
Artemis `public.event` topic, and e-mails each matched Youth Offending Team through
**notificationnotify** with the PDF attached by file-service id. This replaces both the court
register function app and progression's court-register leg. The whole flow is switched between
legacy and new by the single App Configuration flag `YotResultsDistributionService`, which this service's
nightly job reads (fail-closed) as its third reader.

It is a fix-first port: every catalogued defect — the function app's `C` rows and the progression
leg's `P` rows — is fixed or externally owned, each with a register row naming its pinning test
(`doc/DEFECT-FIXES.md`). An appended row carries the same obligations as an original one.

## Programme
Crime Common Platform (CPP) — Modern by Default (MbD)
Organisation: HMCTS / Ministry of Justice — Team: Resulting Assistant
Jira: none — this work carries no ticket; it lands on plain `main`

## Stack
- Spring Boot 4.1, Java 25, **Gradle — never Maven**
- Package: uk.gov.hmcts.cp.yotresultsdistribution
- Port: 8082 (local) / 4550 (Kubernetes)
- Deployment/release name: `yotresultsdistribution-service`
- Provenance: derived from the `hmcts/service-hmcts-crime-springboot-template` crime Spring Boot
  template by way of the `service-cp-crime-informant-register` reference implementation. **Never
  scaffold from scratch and never use Spring Initializr** — the shape of this repo is inherited, and
  a hand-rolled skeleton loses the estate conventions baked into it.

## Key Documentation
| Document | Location |
|---|---|
| **Design (authoritative)** | Confluence — [Court Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004104319/Court+Register+Service) (CRA space). This repo carries **no** design narrative; do not create `doc/*_DESIGN.md`, `SOLUTION_BRIEF.md`, `API_CONTRACTS.md` or `CHANGELOG.md` here |
| Defect-fix register | `doc/DEFECT-FIXES.md` |
| Constitution | `.specify/memory/constitution.md` |
| Specifications | `specs/001-court-register-port/` (complete), `specs/002-consolidate-progression-leg/` (complete), `specs/003-exception-report/` (complete), `specs/004-release-stale-batches/` (complete), `specs/005-operations-rest-api/` (complete), `specs/006-appconfig-connection-string/` (current) |
| Operations API (owned) | `src/main/resources/yot-results-distribution-openapi.yaml`; authorisation rules `src/main/resources/acl/operations-rules.drl` |
| Inbound message schema | `src/main/resources/contracts/distribution-command.schema.json` |
| Register contract (frozen) | `src/main/resources/contracts/progression/` (+ `PROVENANCE.md`) |

## Message-Contract Rule
This service exposes **no business REST API**: nothing about intake, recording, batching, rendering
or notification is reachable over HTTP. Its HTTP surface is actuator plus the **operations API**
under `/operations/**` — the named operator actions that replaced the CLI in increment 005, each
behind `cp-auth-rules-filter` ("Second Line Support" only, identity from the `CJSCPPUID` header) and
`cp-audit-filter-springboot`, and each described in `src/main/resources/yot-results-distribution-openapi.yaml`. Its contracts
are:
- **Inbound**: the `yotresultsdistribution.requests` queue message (`distribution-command.schema.json`,
  `additionalProperties: false`), agreed with `cpp-context-results` (the publisher).
- **Operations API**: `src/main/resources/yot-results-distribution-openapi.yaml`, owned here and versioned with the repo; a
  contract test asserts the controllers against it, and `cp-audit-filter-springboot` reads it at
  runtime to resolve path parameters. Adding a path that is not a named operator action needs a
  constitution amendment (Principle III).
- **Register document**: the `courtRegisterDocument/*` schemas frozen at
  `criminal-court-public-model` 17.103.13 and vendored under `src/main/resources/contracts/progression/`,
  enforced at the write into the register store. Progression no longer receives it.
- **Consumed platform contracts** (this service adapts to them, never redefines them):
  systemdocgenerator `generate-document` (REST command, 202) and its public
  `document-available` / `generation-failed` events; notificationnotify `send-email-notification`
  (REST command, 202); the framework file-service `metadata` + `content` table schema (write-only,
  pinned to changesets 001–006); the App Configuration flag `YotResultsDistributionService`.

Contract changes are cross-team events. The spec-validator agent checks contract compliance, the
defect-fix register, and the operations API's four conditions (authorised, audited, flag-gated where
the command it replaced was, and answering in bounded codes) after implementation.

## Fix-First Rule
The legacy pipeline (the function app for the intake half, progression's leg for the downstream
half) is the oracle for every behaviour NOT catalogued in `doc/DEFECT-FIXES.md`; every catalogued
defect is fixed or externally owned, each with a register row naming its pinning test. A fix without
a row is reverted; an uncatalogued behaviour change needs written sign-off before merge. See
constitution Principle I.

## Cutover Rule
One lever: the App Configuration flag `YotResultsDistributionService`. Never add a second switch (Helm value,
static-data patch, endpoint) that decides which implementation is live. The nightly job reads the
flag once per run with no cache and does nothing when it is off or unreadable; `POST
/operations/batches/generate` reads the same flag at the same point and refuses `FLAG_OFF` unless
the body carries `ignoreFlag: true`, with the override recorded in the audit event and on the run
report. An operations endpoint that lets a person do what a CLI command did is not a second lever;
an endpoint that decided which implementation is live would be. Never run generation with
notification enabled against production data outside cutover.

## Deployment
- **CI/CD**: GitHub Actions → **ADO Pipeline 460** → images to **`crmdvrepo01.azurecr.io`** →
  deployed by **Flux** using the shared **`springboot-app`** Helm chart.
- **Secrets** come from **Azure Key Vault via the CSI driver, with workload identity**. No static
  keys, no committed connection strings, no secret in a Helm value or an environment default.
  **One sanctioned exception** (constitution 5.2.0): the cutover flag is read with the estate's App
  Configuration connection string - the value the WildFly contexts read, from Vault
  `secret/<env>/<stack>/cpp_feature_manager_connection_string_url` - set as
  `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING` in the deployment's values, because no App
  Configuration role can be assigned to this service's identity through `ccm-namespace`. Not the Key
  Vault secret `APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING`: on STE it holds only the store's URL.
  It is one of three **Vault-rendered values** the deployment's ansible sets in `env` (with the
  Redis access key and the system user id), so it is readable from the Deployment spec and the Helm
  release - wider than CSI. Each environment's overlay names its own Vault path; never copy STE's.
  It is still never committed, never defaulted, and never logged or echoed. The
  published local pair committed in `docker-compose.yml` is not a key: it authorises nothing, and
  start-up refuses it wherever a real flag is read.
- The STE wiring (helmsman entry, values, queue terraform, MI exports) lives in the sibling infra
  repos, not here.
- **The operations API's two filter switches default ON.** `AUTHZ_HTTP_ENABLED` and
  `HTTP_AUDIT_ENABLED` both read `true` in `application.yaml` against library defaults of off, so a
  deployment that says nothing is authorised. They are switched off **only by local and test
  configuration** — `docker-compose.yml` and `application-test.yaml`, each saying why where it does
  it — and never by a deployed values file. Start-up never refuses on the combination; what it
  refuses is a value that cannot mean what it says.
- **Being audited takes a third key, and that one is the deployment's.** `HTTP_AUDIT_ENABLED` builds
  nothing on its own: every `audit.http.*` bean sits inside the auto-configuration
  `CP_AUDIT_ENABLED` gates, and this service ships it `false` so a laptop with no broker starts. A
  deployed values file sets `CP_AUDIT_ENABLED=true` with the broker's connection from Key Vault, or
  the API is served unaudited and the pod says so at WARN. `CP_AUDIT_INITIAL_CONNECT_ATTEMPTS`
  (default `2`) bounds how long a call waits before it is refused `503`, because the request event
  is published on the caller's own thread.
- **Five deployment gates sit outside this repository**, and the service has no operational surface
  until they land: the internal route for `/operations/**`, the gateway injecting `CJSCPPUID`, the
  Istio `AuthorizationPolicy` and `NetworkPolicy`, usersgroups reachable from the pod, and the
  Artemis audit connection. See `README.md`'s Operations API section.

## Build & Test
```bash
./gradlew build              # Compile + the full test suite + PMD + Checkstyle (main and test
                             # sources for both) + the JaCoCo coverage gate. Every analysis runs
                             # in `check`; none of them has to be named separately
./gradlew test               # The whole suite: unit and *IT alike — there is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew checkstyleMain checkstyleTest   # Checkstyle alone (google_checks, maxWarnings 0)
./gradlew pmdMain pmdTest    # PMD alone; src/test uses .github/pmd-test-ruleset.xml
./gradlew jacocoTestReport check          # The order CI uses: the coverage report is written
                             # before jacocoTestCoverageVerification reads it, so a failing gate
                             # still leaves a report saying which lines were missed
./gradlew bootRun            # Run locally
```

The operational surface is HTTP, not a command in the image: `docker compose up -d app`, then
`curl -s localhost:8082/operations/flag` and the other six paths. `docker-compose.yml` switches both
estate filters off for the local loop, so no `CJSCPPUID` is needed there. `README.md`'s Operations
API section is the endpoint-by-endpoint table; `specs/005-operations-rest-api/quickstart.md` shows
every refusal.

## Repository Conventions
- Conventional Commits; no AI attribution in commits, PRs, comments or docs.
- TDD red-run convention per `specs/*/tasks.md`: a test task lands its compile-safe seams so the
  recorded red run is a failing assertion, never a compile error; the paired implementation task
  quotes the green run.
- A `DEFECT-FIXES.md` row flips to FIXED only in the commit whose pinning test passes.
- Never run two committing agents concurrently in this repo.

## Setup

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/006-appconfig-connection-string/plan.md` (with `spec.md` and `tasks.md` alongside
it); the completed increments are `specs/001-court-register-port/`,
`specs/002-consolidate-progression-leg/`, `specs/003-exception-report/`,
`specs/004-release-stale-batches/` and `specs/005-operations-rest-api/`.
<!-- SPECKIT END -->
