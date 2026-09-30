<!--
SYNC IMPACT REPORT
==================
Version change: 5.0.3 → 5.1.0
Bump rationale: MINOR - the flag's credential is changed (2026-09-29). The
                Technology Stack said the flag is read on the pod's workload
                identity with an `App Configuration Data Reader` role. That
                role cannot be granted: `ccm-namespace` resolves role names
                through the cluster ConfigMap `azure-info/role-definition`,
                which carries no App Configuration role, and the platform team
                has no date for adding one. Every other reader of the estate's
                flags (resultsvalidator, the WildFly contexts) authorises with
                the shared connection string from Key Vault, so this service
                does the same and the workload-identity read is removed rather
                than kept beside it. No principle's wording changes; the
                Cutover Rule (one flag, read once per run, fail-closed) is
                untouched. MINOR rather than PATCH because a stack choice is
                replaced and a static key is admitted where the stack said
                none; not MAJOR because no principle is redefined.

Proposed in: specs/006-appconfig-connection-string/spec.md. Pinned by
`config/ConfigurationValidationTest.PublishedLocalPair`,
`config/ConfigurationValidationTest.ConnectionStringPrivacy`,
`config/ConfigurationValidationTest.GenerationDownstreams` (the connection
string's shape and https refusals), `config/FeatureFlagPropertiesTest` and
`config/GenerationWiringContextTest.FlagCredential`.

Modified sections (this amendment): Technology Stack - the Feature flag and
Secrets/identity bullets; Increments - 005 marked complete, 006 added as
current. Nothing else; Principles I-VIII untouched. Clarified the same day,
without a version bump (006 T007): the Secrets/identity bullet now names the
published local pair committed in `docker-compose.yml` beside the sanctioned
key, as 006 FR-005 already did - wording only, no rule changes.

Templates / guidance reviewed:
  - CLAUDE.md, README.md, .claude/rules/design_rules.md,
    docker/wiremock/README.md                ⚠ UPDATED in the same commit -
      each described the flag as read on workload identity, or forbade every
      static key.
  - specs/002-consolidate-progression-leg/*  ✅ left as history - research §3
      records the decision this amendment reverses.
  - .specify/templates/*                     ✅ compatible - no change.

Previous amendment (5.0.2 → 5.0.3):
Version change: 5.0.2 → 5.0.3
Bump rationale: PATCH - the owned OpenAPI document is named (2026-09-21). The
                principle called it `src/main/resources/openapi.yaml`; the file
                that landed is `src/main/resources/yot-results-distribution-openapi.yaml`,
                and the difference is load-bearing rather than cosmetic.
                `cp-audit-filter-springboot` finds the document by a SUFFIX glob
                over the whole classpath - `classpath*:**/*` plus the value of
                `audit.http.openapi-rest-spec`, first match wins - so a value of
                `openapi.yaml` matches this service's document and any other
                `*openapi.yaml` a dependency ships, and the parser is handed
                whichever comes back first. The obligation is unchanged: every
                `/operations/**` endpoint is described in the document this
                repository owns, and a contract test asserts it both ways.
                `api/OpenApiContractTest` additionally runs the real glob against
                the real classpath and asserts exactly one match.

Proposed in: specs/005-operations-rest-api/tasks.md, T043's warning. Pinned by
`api/OpenApiContractTest.TheGlobTheAuditFilterResolvesItBy`.

Modified sections (this amendment): Principle III's third contract, and the
Architecture section's operations-API paragraph - the file's name in both.
Nothing else; Principles I, II, IV-VIII untouched.

Templates / guidance reviewed:
  - CLAUDE.md, README.md, .claude/rules/{workflow,technical-rules}.md,
    .claude/agents/{software-engineer,spec-validator,qa}.md
                                             ⚠ UPDATED in the same commit -
      each of them named the file.
  - .specify/templates/*                     ✅ compatible - no change.

Previous amendment (5.0.1 → 5.0.2):
Version change: 5.0.1 → 5.0.2
Bump rationale: PATCH - the architecture section is re-pointed at the mechanism
                increment 004 shipped (2026-09-21). No principle's wording
                changes and no obligation is added, removed or relaxed: what
                was wrong was a description of how a batch nothing was learned
                about ends.

                004 "release-stale-batches" replaced the grace-period
                reconciler and its systemdocgenerator query with a **release
                pass**: the 18:00 run's first act fails every PENDING or
                GENERATING batch past its cutoff `NOT_COMPLETED_BY_NEXT_RUN`
                and releases its rows into that same run's batches, one fenced
                statement per batch. `GenerationReconciler`, the grace period,
                the document query and `GENERATION_TIMED_OUT` are all gone with
                it. The Architecture section still described them, which is the
                kind of stale sentence a reader takes as permission to build
                against a class that is not there.

                Re-pointed here: the **Events** bullet (the outcome a batch
                reaches when neither public event arrives), the **002** entry's
                description of the listener it shipped, and a new **004** entry
                in the Increments list. The prior amendments' Sync Impact
                narratives (3.1.0 and 3.2.0, which name the reconciler and its
                `runId`) are deliberately left as they are: they record what
                was true at the version they announce, and editing them would
                make the register say something that was not.

Proposed in: the increment-004 hand-off, carried out from
specs/005-operations-rest-api at the merge of 004 into this branch. Pinned by
`batch/StaleBatchReleaserTest`, `persistence/StaleReleaseConcurrencyIT` and
`domain/BatchFailureReasonTest`, which are 004's own.

Modified sections (this amendment): Architecture - the Events bullet;
Increments - the 002 entry's closing clause and a new 004 entry. Principles I
to VIII untouched.

Templates / guidance reviewed:
  - .claude/rules/design_rules.md            ✅ already 004's at this merge -
      the flow diagram, the batch state machine and the failure-reason list all
      name the release pass.
  - CLAUDE.md                                ✅ UPDATED at the merge - 004
      listed among the completed increments.
  - .claude/rules/{workflow,technical-rules}.md, .claude/agents/*.md,
    .specify/templates/*                     ✅ compatible - none of them names
      the reconciler.

Previous amendment (5.0.0 → 5.0.1):
Version change: 5.0.0 → 5.0.1
Bump rationale: PATCH - clarification (2026-09-20). 5.0.0 said conditions (a)
                and (b) are carried "by the defaults above and nothing else",
                and named one default for each. For (b) one is not enough:
                `audit.http.enabled` builds no filter on its own, because every
                `audit.http.*` bean the starter declares sits inside the
                `@AutoConfiguration` class `cp.audit.enabled` gates - and this
                service ships that key `false`, so that a laptop with no audit
                broker starts (the transport's connection factory validates its
                hosts and port while it is being constructed). A deployment
                that says nothing was therefore authorised and NOT audited,
                which is not what (b) requires and not what 5.0.0 said.

                (b) now names both keys: `audit.http.enabled` true in this
                service's configuration, and `cp.audit.enabled` true in every
                deployed values file with the broker's connection from Key
                Vault. Nothing enforced changes - no cross-field refusal, the
                pod still always comes up, the value-shape refusals are
                untouched - so PATCH: the obligation is the one 5.0.0 already
                stated, said in full.

                What the service does about the gap it cannot refuse: one WARN
                at start-up naming both settings, because the filter that would
                have published is never constructed and the one that is
                swallows its own publishing failures, so a deployment that
                forgot the transport otherwise looks exactly like one that has
                it.

Proposed in: specs/005-operations-rest-api/spec.md, the amendment section's
fourth proposal, and FR-045. Pinned by
`ConfigurationValidationTest.ShippedConfiguration`
(`the_audit_transport_should_ship_switched_off_for_a_laptop`) and
`ConfigurationValidationTest.OperationsSettings`
(`an_audit_filter_over_a_transport_that_is_off_should_say_so_at_start_up`,
`a_pod_that_publishes_its_audit_events_should_say_nothing_about_them`).

Modified sections (this amendment): Principle III - condition (b) names the
audit transport key beside the HTTP filter's, and the "How (a) and (b) are
carried" paragraph says which default carries which condition. Nothing else
changes; Principles I, II, IV-VIII untouched.

Templates / guidance reviewed:
  - .claude/rules/design_rules.md            ⚠ UPDATED - the operations-API
      section's "Both filters are on by default" bullet named one audit key.
  - specs/005-operations-rest-api/plan.md    ⚠ UPDATED - the settings table's
      `cp.audit.enabled` row read "`true` deployed" of a tree that ships it
      false unless CP_AUDIT_ENABLED is set.
  - CLAUDE.md, .claude/rules/{workflow,technical-rules}.md,
    .claude/agents/*.md, quickstart.md       ✅ compatible - none of them
      describes which key builds the audit filter.
  - .specify/templates/*                     ✅ compatible - no change.

Previous amendment (4.1.0 → 5.0.0):
Version change: 4.1.0 → 5.0.0
Bump rationale: MAJOR - Principle III's conditions (a) and (b) are redefined
                (2026-09-20, design owner). 4.1.0 made them start-up refusals:
                a pod that set `yotresultsdistribution.servicebus.namespace` MUST refuse
                to start with the operations API enabled and either
                `authz.http.enabled` or the audit path off. 5.0.0 says the pod
                starts. The two switches are ordinary configuration an operator
                may turn on or off, and what carries the conditions instead is
                the DEFAULT: both are `true` in `application.yaml` and in every
                deployed values file, which is the opposite of the two
                libraries' own defaults and is the half of the old rule that
                was load-bearing.

                MAJOR and not MINOR: relaxing a NON-NEGOTIABLE condition is a
                redefinition. A deployed environment may now do something it
                could not do before - start with a filter switched off - and a
                reader of 4.1.0 would get that wrong. The local-loop exemption
                4.1.0 added is REMOVED rather than widened, because what it was
                an exemption from no longer exists; so is the
                `yotresultsdistribution.servicebus.namespace` discriminator, which made
                a laptop and a deployed pod two different products.

                Why the conditions survive the relaxation: a deployment that
                says nothing about either switch is authorised and audited, the
                deployment gates in the increment's spec still require the audit
                connection in STE, and every endpoint is still reviewed for its
                allow rule and its audit coverage. What is gone is a pod that
                would not come up on a configuration an operator had chosen
                deliberately - which is not a security control, it is a service
                that cannot be operated.

Proposed in: specs/005-operations-rest-api/spec.md, the amendment section's
third proposal, and FR-045/FR-053. Pinned by two nested classes of
`ConfigurationValidationTest`: `ShippedConfiguration`, whose cases are the two
defaults being true, and `OperationsSettings`, whose cases are a pod with both
switches off starting, the two single-switch counterparts, and the value-shape
refusals that remain.

Modified sections (this amendment): Principle III - conditions (a) and (b) are
restated as default-on configuration, and the "Where (a) and (b) are enforced"
paragraph and the local-loop exemption paragraph are removed with the refusal
they described. Nothing else changes; Principles I, II, IV-VIII untouched.

Templates / guidance reviewed:
  - .claude/rules/design_rules.md            ⚠ UPDATED - the operations-API
      section's "Both filters are enforced at start-up" bullet described the
      refusal and is rewritten to the defaults.
  - specs/005-operations-rest-api/quickstart.md ⚠ UPDATED - its "Local" section
      said a deployed pod refuses to start.
  - CLAUDE.md, .claude/rules/{workflow,technical-rules}.md,
    .claude/agents/*.md                      ✅ compatible - none of them
      states a start-up refusal for either switch.
  - .specify/templates/*                     ✅ compatible - no change.

Previous amendment (4.0.0 → 4.1.0):
Bump rationale: MINOR - conditions (a) and (b) of Principle III's operations
                API gain the environment they are enforced in, and a named
                local exemption (2026-09-20). 4.0.0 wrote both as obligations
                on "every endpoint" with no scope, and increment 005's FR-045
                and FR-053 enforce them as start-up refusals on a **deployed**
                pod only - on the `yotresultsdistribution.servicebus.namespace`
                discriminator this service already draws deployment on - while
                the local loop documented in the increment's quickstart serves
                the endpoints with both filters off.

                A review found the gap the right way round: a spec may not
                grant itself an exemption from a condition stated here, so
                either the code drops the discriminator or the constitution
                records the exemption. It records it, because the two filters
                are estate libraries that need an estate to talk to -
                usersgroups for the caller's groups and an Artemis audit broker
                for the events - and neither exists on a laptop, while
                FR-044 serves the endpoints by default so an unconditional
                reading would stop `bootRun`, `docker compose up` and the
                container smoke until the endpoints were switched off in the
                committed `application.yaml`, which FR-044 forbids.

                MINOR rather than PATCH: the scope of a NON-NEGOTIABLE
                condition is materially narrowed, which a reader of 4.0.0 would
                get wrong, and a new obligation is added with it - the two
                conditions are now enforced *as start-up refusals* wherever the
                service is deployed rather than left to a library default.
                MINOR rather than MAJOR: no endpoint that was forbidden becomes
                permitted, and no deployed environment may do anything it could
                not do before - on a deployed pod the conditions are stricter
                than 4.0.0 left them, not weaker.

Proposed in: specs/005-operations-rest-api/spec.md, FR-045 and FR-053, and
assumption 9. Pinned by `ConfigurationValidationTest.OperationsRefusals`, whose
two "should start" counterparts are the exemption itself.

Modified sections (this amendment): Principle III - conditions (a) and (b) each
gain the sentence saying where they are enforced, and a new paragraph after the
four conditions names the discriminator, the refusals and the one supported way
to have the endpoints unguarded. Nothing else changes; Principles I, II, IV-VIII
untouched.

Templates / guidance reviewed:
  - .claude/rules/design_rules.md            ✅ "The operations API" section is
      unchanged in substance and remains true: the filters are how the surface
      is permitted, and the local loop is not a deployed environment.
  - CLAUDE.md, .claude/rules/{workflow,technical-rules}.md,
    .claude/agents/*.md                      ✅ compatible - none of them
      states the conditions' environment, so none of them is now wrong.
  - .specify/templates/*                     ✅ compatible - no change.

Previous amendment (3.2.0 → 4.0.0):
Bump rationale: MAJOR - Principle III is redefined (2026-09-19, design owner).
                The principle read "This service has **no business REST API**"
                and closed with "The only HTTP this service exposes is Spring
                Boot Actuator. There is no OpenAPI file, and adding a business
                endpoint requires a constitution amendment, not just a spec.
                Operational actions are a CLI in the image." That last sentence
                is what increment 005 invalidates: the six operations commands
                become seven REST endpoints under `/operations/**` and the CLI
                is removed from the image altogether.

                What is kept is the part that was load-bearing. There is still
                **no business REST API**: nothing about intake, recording,
                batching, rendering or notification is reachable over HTTP, and
                the register flow stays message-driven end to end. What is
                added is a narrow, named exception for an **operations API**,
                and it is an exception with four conditions rather than a
                permission: every endpoint MUST be behind
                `cp-auth-rules-filter` with an explicit allow rule naming the
                groups admitted, MUST be audited by
                `cp-audit-filter-springboot`, MUST be gated by the
                `YotResultsDistributionService` flag at least as strictly as the CLI
                command it replaces was (with any override recorded in the
                audit event and on the run report), and MUST answer under
                Principle VII -
                bounded codes, counts and identifiers only, no defendant
                detail, no operator input echoed back.

                Why it is MAJOR rather than MINOR: an endpoint was previously
                forbidden and is now permitted, the CLI the principle named as
                the operational surface ceases to exist, and the repo gains an
                OpenAPI document as a **third contract it owns** - three
                statements of existing practice that a reader of 3.2.0 would
                get wrong. The rationale is the estate's: this is the shape
                every other CPP Spring Boot service has (the reference
                implementation is `service-cp-crime-hearing-results-validator`),
                and the two estate starters give the authentication and the
                audit trail that `kubectl exec` never could - an exec'd command
                runs as the pod, leaves no audit event, and is available to
                anyone with exec rights on the namespace rather than to a named
                user group.

                Callers: the usersgroups group "Second Line Support" only, on
                every endpoint, identity taken from the `CJSCPPUID` header,
                which the internal ingress injects after authenticating and
                after stripping whatever the client sent.

                Condition (c) is stated as "at least as strictly as its
                command was" rather than "exactly where its command was",
                because one endpoint has to be gated more strictly than its
                command: `supersede-before` read the flag nowhere, and an
                unconditional HTTP mutation that makes this service give up a
                period of registers is a second lever however carefully it is
                authorised. It is admitted only while the same uncached read
                says the flag is OFF, and it has no override.

Proposed in: specs/005-operations-rest-api/spec.md, section "Constitution
amendment proposal (Governance step 1)", as the Governance amendment procedure
requires. **Order note**: the amendment commit (`d73ef50`) was written before
the spec commit (`2015c35`) rather than after it, which is the wrong way round -
Governance step 1 is the proposal and step 2 the bump. The proposal was added to
the spec in the same branch and before any code landed, so nothing was built
under an unproposed principle, but the order is recorded here rather than tidied
away. Governance step 3 (the /speckit-analyze re-run against every in-flight
feature spec) is task T0A of that increment and covers 005 and the concurrent
004 "release-stale-batches", which is read and never edited from this branch.

Modified sections (this amendment): Principle III - the opening sentence, a new
"The operations API" clause with its four conditions, the OpenAPI document added
to the owned contracts, and the closing "only HTTP ... is Actuator / operations
are a CLI" bullet replaced; the "One lever" bullet refined (an operations
endpoint that lets a person do what a CLI command did is not a second lever, and
no endpoint may decide which implementation is live); Technology Stack & Deployment
"HTTP surface" bullet rewritten; the Increments list gains 005. Principles I, II,
IV-VIII unchanged in substance - Principle VII is not amended but is now cited by
Principle III's condition (d), which adds no obligation it did not already carry.

Templates / guidance reviewed:
  - .specify/templates/plan-template.md      ✅ compatible - the Constitution
      Check block is filled per feature; 005's plan gates on III as redefined.
  - .specify/templates/spec-template.md      ✅ compatible - behaviour is still
      expressed as message in, record/command out; the operations API is
      expressed as operator actions with their refusals, not as a business flow.
  - .specify/templates/tasks-template.md     ✅ compatible - no change.
  - .specify/templates/checklist-template.md ✅ compatible - no change.
  - CLAUDE.md                                ✅ updated in this commit - the
      Message-Contract Rule names the operations API and its OpenAPI document,
      the Cutover Rule loses the "regeneration CLI" sentence, the Key
      Documentation and Setup pointers name specs/005.
  - .claude/rules/design_rules.md            ✅ updated in this commit - the
      opening paragraph, the `api/` inbound adapter in the package structure,
      the Cutover Rule's wording about endpoints, and "Out of Scope - Any REST
      API" replaced by the four conditions.
  - .claude/rules/workflow.md                ✅ updated in this commit - the
      contract section gains the OpenAPI file as the third owned contract and
      the spec-validator checks the operations API against the four conditions
      instead of "the absence of REST".
  - .claude/rules/technical-rules.md         ✅ updated in this commit -
      `@ControllerAdvice` and `ProblemDetail` permitted for the operations API
      only, and nowhere else.
  - .claude/agents/software-engineer.md      ✅ updated in this commit.
  - .claude/agents/code-reviewer.md          ✅ updated in this commit.
  - .claude/agents/spec-validator.md         ✅ updated in this commit - gate 8
      is now "the operations API's four conditions" rather than "the absence of
      a REST API".
  - .claude/agents/qa.md                     ✅ updated in this commit - MockMvc
      slice tests are required for the operations API rather than forbidden.
  - README.md                                ✅ updated in this commit - the
      "exposes no REST API" paragraph.
  - doc/DEFECT-FIXES.md                      ✅ deliberately untouched: replacing
      an operational surface is not a deviation from a legacy oracle, and
      neither the function app nor progression's leg had one.

Previous amendment (3.1.0 → 3.2.0):
Bump rationale: MINOR - increment 003 adds an obligation without changing a
                principle's wording (2026-09-15). Two things this service must
                now do that it did not have to before:

                The morning report's output is **structured events**, not prose.
                `yotresultsdistribution_exception` carries one exception per line and
                `yotresultsdistribution_exception_report` one summary per run, each field
                reaching the encoder as a field rather than rendered into the
                message text - because Log Analytics has to read `kind`,
                `request_id` and `batch_id` as columns, and a value inside a
                message is a value every saved query has to parse back out.
                Principle VII's bounded-code rule governs every one of those
                fields and labels exactly as it governs a log line; what is new
                is that the obligation now falls on an indexed event as well.

                And the `runId` Principle VII names for a scheduled run now
                covers **three** runs rather than two: the 18:00 generation run,
                its grace-period reconciler, and the 07:00 report. The sweep's
                own lines carry it too where it runs inside a run, and mint one
                where they do not - `batch/RunCorrelation` is unchanged and
                handles the nesting it already handled, but the rule now has a
                third caller and a fourth kind of line under it.

                No principle's wording changes. Principles I to VIII stand
                exactly as amended at 3.1.0.

Modified sections (at 3.2.0): the Increments list only - 003
"exception-report" added, 002 moved from "current" to "complete".

Templates / guidance reviewed (at 3.2.0):
  - .claude/rules/design_rules.md   ✅ updated in the same increment (T074): the
      07:00 leg and the sweep in the two-legs diagram, the fourteen ports, the
      two new permitted repository readers, and the statement that the report is
      not on the cutover lever's circuit.
  - README.md                       ✅ Status entry for 003 (T073), including the
      e-mail output's deployment gate on the notificationnotify template.
  - CLAUDE.md                       ✅ Specifications row and Setup pointer (T076).
  - doc/DEFECT-FIXES.md             ✅ deliberately untouched: a new capability is
      not a deviation from a legacy oracle, and inventing a C or P number for one
      would make the register say a defect existed where none was catalogued.

Previous amendment (3.0.1 → 3.1.0):
Bump rationale: MINOR - Principle VII's correlation rule is made satisfiable,
                and doing so adds an obligation (2026-09-10). The rule said
                every log line about processing MUST carry `requestId` and
                `hearingId`. A scheduled run has neither and never could: it is
                one unit of work across many hearings and many batches, and
                those identifiers belong to the deliveries that recorded the
                registers rather than to the night that renders them. So the
                eleven lines a night writes - four from the nightly job, seven
                from the grace-period reconciler - stood in permanent breach of
                a rule that could not be met, and carried no correlation at all:
                a night could not be pulled out of the estate's index as one
                thing, which on an evening where the reconciler is also settling
                earlier nights' batches is the difference between reading a run
                and reading a haystack.

                The rule now names the correlation appropriate to the unit of
                work - `requestId` + `hearingId` for a delivery, `runId` for a
                scheduled run, the batch id for a line about one batch - and
                says what it was always for: no line is unattributable. The new
                obligation is the `runId`, which is why this is MINOR and not a
                wording PATCH.

                Implemented by `batch/RunCorrelation`, opened by
                `RegisterGenerationJob.run()` and by
                `GenerationReconciler.reconcileScheduled()`. Nesting is handled
                rather than assumed: a sweep the run reaches into adopts the
                run's id, a sweep that fired on its own schedule mints one, and
                only whoever opened it removes it - the scheduler's threads are
                pooled, and an id left behind would be inherited by whatever ran
                next on that thread, which is worse than no correlation because
                it reads as a true one.

Modified sections (this amendment): Principle VII, the correlation bullet only.
Principles I-VI and VIII unchanged.

Templates / guidance reviewed:
  - .claude/rules/design_rules.md   ✅ already said it (2026-09-10, T073): "the
      batch id on the generation leg". Now also names the run id.
  - The run report line gains `run_id` as its second field, so the metrics note
      handed over for the Confluence page moves from twenty-one fields to
      twenty-two. Corrected in that note before handover.

Earlier amendment (3.0.0 → 3.0.1):
Bump rationale: PATCH - the commit-type list is completed (2026-09-05). The
                Commits bullet of Principle VIII (Estate Conventions) listed six
                Conventional Commit types (feat, fix, chore, docs, refactor, test), but
                `main` already carries three `ci:`, three `build:` (two of them
                dependabot's `build(deps)`) and one `style:` commit, so the
                list described the repository's practice incompletely and an
                author following it had nowhere to put a workflow or a
                dependency bump. The list now names all nine and says what the
                three added types cover. No principle changes and no obligation
                is added or removed.

                Also recorded: the Phase 1 commit that declared the 002
                configuration keys was written with `config:`, which is NOT in
                the list - a configuration change is `chore:` (or `build:` when
                it is tooling). That commit has since been reworded to
                `chore(config):`, so history and the list agree.

Modified sections (this amendment): Principle VIII, Commits bullet only.
Principles I-VII unchanged; Principle VIII changed only in that bullet (the
accepted commit-type list), with no new obligation.

Templates / guidance reviewed:
  - specs/002-consolidate-progression-leg/tasks.md  ✅ aligned (2026-09-05):
      its Conventions paragraph names the same nine types.

Previous amendment (2.0.4 → 3.0.0):
Bump rationale: MAJOR — Principle III is redefined (2026-09-05). The
                outbound contract is no longer "the progression-owned
                add-court-register command, POSTed once per hearing": the
                register document is written to this service's own register
                store, and the service becomes a client of four platform
                contracts it consumes but does not own — systemdocgenerator's
                generate-document command and its document-available /
                generation-failed public events, notificationnotify's
                send-email-notification command, the framework file-service
                table schema (write-only), and the YotResultsDistributionService App
                Configuration flag. The Technology Stack section's "contains
                no scheduler, no PDF generation, no GOV.UK Notify code" and
                "progression's leg is out of scope" statements are reversed.
                Decision recorded in the design (Confluence "Court Register
                Service", rev 2 / 2.1, §10) and specs/002-consolidate-
                progression-leg/spec.md.

Modified sections (this amendment): Principle III (redefined — contracts
enumerated, ownership per contract, the register store as the outbound
boundary, the flag as a contract); Technology Stack & Deployment (Idempotency
bullet — supersession at the write replaces the "absorbed downstream"
argument; Outbound bullet — record, batch, render, notify; new Scheduling,
Events, Second datasource and Feature flag bullets; Deployment bullet; Out of
scope bullet — progression's leg leaves it, its retirement is named);
"Current increment" → 002 "consolidate-progression-leg" with 001 recorded as
complete. Principles I, II, IV–VIII unchanged in substance; Principle I's
catalogue widens to the progression-leg P rows by the append mechanism it
already carries.

Templates / guidance reviewed:
  - .specify/templates/plan-template.md       ✅ compatible — Constitution
      Check is filled per feature; plan authors gate on III as redefined.
  - .specify/templates/spec-template.md       ✅ compatible — behaviour is
      expressed as queue message in, register record + platform commands out.
  - .specify/templates/tasks-template.md      ✅ compatible.
  - CLAUDE.md                                 ✅ aligned (2026-09-05): contract
      rule, cutover rule, Confluence as the design authority.
  - README.md                                 ✅ aligned (2026-09-05).
  - .claude/rules/design_rules.md             ✅ aligned (2026-09-10, T073):
      rewritten for the 002 shape - the two legs, the batch state machine
      beside the request one, the topic beside the queue, supersession at
      the write in place of the absorbed-duplicate-POST argument, the four
      consumed platform contracts, and the one-lever Cutover Rule.
  - .claude/rules/workflow.md                 ✅ aligned (2026-09-05).
  - .claude/rules/technical-default.md        ✅ resolved by removal
      (2026-09-10). Raised at T074 as stale - its Description, Outbound row
      and no-REST bullet still named the POST to progression - and ruled on
      after Phase 9 closed: **folded into CLAUDE.md and deleted**, rather
      than rewritten. Four of its six sections (Service Identity, Technology
      Stack, Build & Test Commands, Key Documentation) duplicated CLAUDE.md
      and its Constraints overlapped the three Rules there and
      technical-rules.md, and that duplication is how it drifted - the 002
      documentation sweep (`a1cbcab`) edited only its Key Documentation
      table and left the rest describing the 001 shape. Every rule file in
      `.claude/rules/` is loaded into each session, so a stale duplicate was
      being read as authority, not merely sitting on disk. The twelve facts
      that lived only there are now in CLAUDE.md: the release name
      `yotresultsdistribution-service`, the CI/CD chain (GitHub Actions → ADO
      Pipeline 460 → `crmdvrepo01.azurecr.io` → Flux, `springboot-app`
      chart), the Key Vault CSI + workload identity secrets rule, the
      template provenance and the never-Maven/never-Initializr/never-
      scaffold constraint, the organisation, and the no-Jira note. Three
      rule files remain: design_rules.md, technical-rules.md, workflow.md.
  - .claude/agents/spec-validator.md          ✅ aligned (2026-09-10, T073):
      "The Four Contracts" is now eight in three groups - two owned, four
      consumed, two properties of this service's shape - and the scope gate
      names 002 with both audits as per-build assertions.
  - .claude/agents/software-engineer.md       ✅ aligned (2026-09-10, T073):
      record-not-POST, ids-before-calls, learn-outcomes-never-assume-them,
      the batch statuses, the one lever, a counter on every drop.

Previous amendment (2.0.3 → 2.0.4):
Bump rationale: PATCH — wording (2026-09-05). The repo no longer carries
                doc/API_CONTRACTS.md or doc/openapi.yaml; Principle III and
                the Development Workflow now name the Confluence design page
                and the JSON schemas as where the contracts are documented.

Previous amendment (2.0.2 → 2.0.3):
Bump rationale: PATCH — static-analysis reality sync (2026-09-01). The build
                gained no new obligation; what it already required is now
                actually enforced, and Principle VIII's static-analysis bullet
                is corrected to describe the build as it is. PMD is pinned;
                `pmdMain` lost the `onlyIf` that kept it out of `build` and
                joins `check` with `pmdTest` (its own test ruleset);
                `checkstyleTest` is enabled with a suppressions file; the
                coverage report is written before the gate reads it; and the
                "thresholds are a ratchet" sentence, previously honour-based,
                is enforced by a pull-request guard. No principle's
                requirements change.

Modified sections (this amendment): Principle VIII "Static analysis" bullet
(rewritten to name the pinned PMD version, both PMD tasks and both Checkstyle
tasks as members of `check`, the test ruleset and suppressions file, the report
ordering, and the ratchet guard); the Development Workflow "Required to run
cleanly before merge" list (PMD is no longer the analysis `build` skips, so its
separate bullet and the `jacocoTestReport` bullet are restated). Decision
recorded at specs/001-court-register-port/research.md §17.

Previous amendment (2.0.1 → 2.0.2):
Bump rationale: PATCH — the register grew its first appended row (C35,
                2026-09-01: the hearing-date wall-clock legs, found in the
                pipeline-core review). Principle I already provided the
                append mechanism in its final bullet; this amendment makes
                the catalogue language match it: "the 34" now reads as the
                design document's original catalogue (C1–C34) PLUS any row
                appended later under review, with appended rows carrying
                exactly the same obligations (fix specification, pinning
                test, sign-off state). No principle's requirements change.

Modified sections (this amendment): Principle I opening paragraph and first
bullet — "the 34 behaviours that are catalogued" widened to "the catalogued
behaviours (C1–C34 from the design document §7, plus rows appended under
review — C35 onward)"; the pre-approval sentence now names the original
catalogue explicitly, since an appended row is approved by the review that
appends it, not in advance.

Previous amendment (2.0.0 → 2.0.1):
Bump rationale: PATCH — delivery-scope precision after the P0 review
                (2026-08-31): the register carries 34 rows, but three of them
                (C18, C28, C34) are remediations owned outside this repository
                (the legacy function-app repo and the producer). The
                constitution now states the scope consistently everywhere:
                31 defects are fixed in this service; C18/C28/C34 are
                externally-owned remediations registered as PENDING with an
                owner and a trigger, tracked to conclusion before cutover.
                No principle's requirements change.

Modified sections (this amendment): the preamble delivery claim, Principle I
first bullet, and the Current-increment section — "all 34 fixed" reworded to
the 31-plus-3 form above.

Previous amendment (1.0.2 → 2.0.0):
Bump rationale: MAJOR — Principle I is rewritten from parity-first to
                fix-first, a redefinition that invalidates the previous
                practice of porting catalogued defects bug-for-bug. This
                constitution was adopted from service-cp-crime-informant-
                register at its 1.0.2 and re-ratified for
                service-cp-crime-yot-results-distribution (2026-08-31, user decision):
                the court-register port fixes all 34 catalogued legacy
                defects (C1–C34) outright and documents every fix in
                doc/DEFECT-FIXES.md. The legacy pipeline remains the oracle
                for every behaviour NOT catalogued; nothing else about the
                engineering discipline is relaxed.

Modified principles (this amendment):
  - I. Behaviour-Parity First → I. Defect-Fix-First with Characterised
    Legacy Behaviour — retitled and rewritten. Golden files now encode FIXED
    behaviour; the deviations register is replaced by the defect-fix register
    (doc/DEFECT-FIXES.md); the differential audit fails any catalogued defect
    still reproduced AND any uncatalogued behaviour change.
  - II. Test-Driven Development — one addition: every defect fix carries a
    test that would fail against the legacy behaviour and passes against the
    fix, and that test is named in the fix's DEFECT-FIXES row.
  - III. Message-Contract First — re-targeted at this service's contracts
    (inbound yotresultsdistribution.requests; outbound progression-owned
    add-court-register, 202-only). New clause: a fix that changes the shape
    of an outbound component is a cross-team event requiring progression-team
    notification before ship.
  - IV–VII — re-targeted at the court register flow (single fragment,
    youth defendants only, PDF produced downstream in progression); the
    requirements themselves are unchanged.
  - VIII. Estate Conventions — package root uk.gov.hmcts.cp.yotresultsdistribution;
    branch policy is plain main with no ticket prefixes (user decision,
    2026-08-31); everything else unchanged.
  - Technology Stack & Deployment, Development Workflow & Quality Gates,
    Governance — rewritten for the court register: queue, processed-log key
    (no fan-out), progression gateway, golden-file rule re-pointed at
    DEFECT-FIXES.md with polarity flipped.

History:
  - 5.0.0 (2026-09-20) Principle III's conditions (a) and (b) redefined: the
    filters are enabled by DEFAULT in this service's configuration and in every
    deployed values file, and switching either off is a deliberate
    configuration act of the operator. The start-up refusal 4.1.0 introduced,
    its `yotresultsdistribution.servicebus.namespace` discriminator and the local-loop
    exemption it needed are all removed; the pod always starts.
  - 4.1.0 (2026-09-20) Principle III's conditions (a) and (b) gain the
    environment they are enforced in - a start-up refusal wherever the service
    is deployed, on the `yotresultsdistribution.servicebus.namespace` discriminator -
    and the local loop is recorded as the one exemption, at the constitution
    rather than in a spec.
  - 4.0.0 (2026-09-19) Principle III redefined: the "no REST at all, operations
    are a CLI" clause becomes "no *business* REST API, and an operations API
    only under four conditions - authorised, audited, flag-gated where the
    command it replaces was, and answering under Principle VII". The OpenAPI
    document joins the contracts this service owns; the CLI is removed by
    increment 005.
  - 3.2.0 (2026-09-15) Increment 003's obligations: the report's output is
    structured events, and `runId` covers a third scheduled run.
  - 3.1.0 (2026-09-10) Principle VII's correlation rule made satisfiable for a
    scheduled run, which gains a `runId` of its own.
  - 2.0.3 (2026-09-01) Principle VIII static-analysis reality sync: PMD pinned
    and both PMD tasks in `check` (test sources on their own ruleset),
    `checkstyleTest` enabled with a suppressions file, coverage report ordered
    before the gate, and the threshold ratchet enforced on pull requests.
  - 2.0.2 (2026-09-01) Catalogue language widened to admit appended rows
    (C35 onward) with the same obligations as C1–C34.
  - 2.0.1 (2026-08-31) Delivery scope stated as 31-in-service plus three
    externally-owned remediations (C18/C28/C34), after the P0 review.
  - 2.0.0 (2026-08-31) Re-ratified for service-cp-crime-yot-results-distribution.
    Principle I inverted to fix-first with the 34-entry defect-fix register;
    all sections re-targeted at the court-register flow. Lineage: adopted
    from service-cp-crime-informant-register constitution 1.0.2.
  - 1.0.2 (2026-08-21) [informant lineage] Principle VIII static-analysis
    reality sync: Checkstyle + coverage gate adopted; merge checklist updated.
  - 1.0.1 (2026-08-20) [informant lineage] Principle IV Jackson coordinates
    made version-neutral.
  - 1.0.0 (2026-08-20) [informant lineage] Initial ratification.

Added sections: None (structure carried over).
Removed sections: None.

Templates requiring updates:
  - .specify/templates/plan-template.md       ✅ compatible — the "Constitution
      Check" block is filled per-feature by /speckit-plan; plan authors MUST
      gate on Principles I–VIII as amended.
  - .specify/templates/spec-template.md       ✅ compatible — spec authors MUST
      express behaviour in terms of the queue message in and the
      progression.add-court-register command out (Principle III), never REST
      endpoints, and MUST cite the C-number for any behaviour that a fix
      changes.
  - .specify/templates/tasks-template.md      ✅ compatible — already carries
      the local amendment making test tasks mandatory and ordered before the
      implementation they guard (Principle II); no further change needed.
  - .specify/templates/checklist-template.md  ✅ compatible — no changes.
  - CLAUDE.md                                 ✅ aligned — message-contract rule
      in place of any API-first rule; SPECKIT block points at
      specs/001-court-register-port/plan.md.
  - .claude/rules/*.md                        ✅ aligned — retained as
      quick-reference; this constitution is authoritative where they disagree.

Follow-up TODOs: None. All placeholders resolved.
-->

# service-cp-crime-yot-results-distribution Constitution

This service is a Spring Boot replacement, on AKS, of the whole court-register
flow: the Node.js function app (the `CourtRegister*` pipeline in
`cpp-context-azure-legalaidagency/azure-functions/durable-functions/`) and,
since increment 002, progression's court-register leg. It consumes thin
hearing-resulted messages from a dedicated Azure Service Bus queue, assembles
**one court register per hearing** covering **youth defendants only**,
matches recipients via NOW-subscription rules keyed on the court centre, and
**records** the result in its own register store; at 18:00 Europe/London on
weekdays it batches the recorded registers per court centre and register
date, renders each batch through systemdocgenerator (the unchanged
`OEE_Layout5` template) and e-mails it through notificationnotify. The
legacy flow fails silently on both halves and carries 34 catalogued
function-app defects (C1–C34) and 9 catalogued progression-leg defects
(P1–P9); this service exists to end that. Unlike its informant-register
predecessor, **this is deliberately not a bug-for-bug port**: thirty-one of
the thirty-four function-app defects are fixed in this service, the
remaining three (C18, C28, C34) are externally-owned remediations tracked to
conclusion before cutover; six of the nine progression-leg defects are fixed
by construction, two retire with the leg and one is moot; and every fix is
registered. Which implementation is live — legacy or this service, intake
and generation together — is decided by one App Configuration flag.

## Core Principles

### I. Defect-Fix-First with Characterised Legacy Behaviour (NON-NEGOTIABLE)

The legacy implementation is the **oracle for every behaviour that is not
catalogued as a defect**: for the intake half, the JavaScript function app
under `cpp-context-azure-legalaidagency/azure-functions/durable-functions/`
together with its Jest fixtures; for the downstream half (increment 002),
progression's court-register classes at `cpp-context-progression` `main`
`79edf7cf3d` — `CourtRegisterPdfPayloadGenerator`, `CourtRegisterHandler`,
`CourtRegisterRequestRepository`, `CourtCentreAggregate`,
`CourtRegisterEventProcessor` — together with their tests, whose outputs are
recorded as goldens by executing those classes. For the catalogued
behaviours — C1–C34 from the court-register design §7 and P1–P9 from its
§7.3, plus any row appended to this repo's `doc/DEFECT-FIXES.md` under
review (C35 onward) — the fixed behaviour specified in the register is the
requirement, and reproducing the defect is itself a defect. An appended row
carries exactly the same obligations as an original one: a fix
specification, a pinning test, and a sign-off state.

- The 34 original catalogued defects (C1–C34) are **pre-approved fixes**: no
  further authorisation is needed to implement them; a row appended later is
  approved by the review that appends it. **Thirty-one are implemented in
  this service; C18, C28 and C34 are externally-owned remediations** (the
  legacy function-app repo and the producer), registered as PENDING with an
  owner and a trigger and tracked to conclusion before cutover. Fixes whose
  output is
  business-visible (content, recipients, dates, the PDF) carry a
  **sign-off-before-cutover** marker in the register — sign-off gates
  deployment, never implementation.
- Every fix MUST have a row in `doc/DEFECT-FIXES.md` carrying: the defect
  reference (C-number), the legacy behaviour with `file:line` citations, the
  fixed behaviour, the rationale/impact, **the test that pins the fix**, and
  the sign-off status. **A fix merged without a DEFECT-FIXES entry MUST be
  reverted** or registered retrospectively with named approval.
- Golden files and fixtures encode **fixed** behaviour. Legacy Jest fixtures
  are the raw material, repaired where the design document proves them stale
  or vacuous (wrong vocabulary key set, `.csv` filenames, mis-spelled field
  names); every repair is recorded in the fixture's provenance note.
- Any behaviour change that is **not** on the register is an uncatalogued
  deviation: it requires the same written sign-off the old parity regime
  demanded, before merge. "It looked wrong so I fixed it" is not a category —
  either it gets a C-number appended to the register with review, or the
  legacy behaviour stands.
- The **differential audit** (the final increment) replays a recorded corpus
  through the legacy Node oracle and the ported pipeline and compares:
  every difference MUST map to a C-number. A catalogued defect still
  reproduced fails the audit; an unexplained difference fails the audit.
  Both are build-blocking.

**Rationale**: the informant port proved the parity-first discipline works,
and its replays then proved the same defects were silently losing registers
in production-shaped data. The business decision for this flow (2026-08-31)
is to stop carrying known defects forward. What parity protected — the
thousands of uncatalogued behaviours downstream consumers depend on — is
still protected: the oracle, the twins, and the differential audit remain;
only the 34 catalogued behaviours are, deliberately and traceably, different.

### II. Test-Driven Development (NON-NEGOTIABLE)

Red → Green → Refactor for every behaviour change, without exception.

1. Write the failing test first. It MUST run and fail for the *correct* reason
   — the assertion, not a missing class or a compile error.
2. Write the minimum production code to make it pass.
3. Refactor with the test still green.

Because "it really did fail first" cannot be proved from a commit graph, the
evidence is a convention that a reviewer can audit:

- Test tasks precede implementation tasks in every task list, and each test
  task is closed before the implementation task it guards is opened.
- Each task's commit narrative records the observed red run before the green
  run — the failing assertion, quoted.
- Reviewers reject implementation commits whose tests could not have failed
  first: assertions that are tautologically true, tests that assert only that
  no exception was thrown, coverage added in the same breath as the code with
  no red run recorded.
- **Every defect fix carries a test that would fail against the legacy
  behaviour and passes against the fix.** That test is named in the fix's
  `doc/DEFECT-FIXES.md` row; a fix row with no named pinning test is
  incomplete and blocks merge.

The `qa` reviewer agent gates on that convention. Production code arriving
without an accompanying failing-then-passing test is a FAIL, not a style
comment.

Exempt: pure mechanical refactors (rename, move, extract with no behaviour
change), formatting, and comment-only edits.

**Rationale**: fix-first (Principle I) is only meaningful if it is
executable. A test written after the code encodes what the code does; a test
written from the register encodes what the fix is supposed to do. Only the
second one protects the register — in both senses.

### III. Message-Contract First (NON-NEGOTIABLE)

This service has **no business REST API**. The register flow is message-driven
end to end, and nothing about intake, recording, batching, rendering or
notification is exposed over HTTP: no hearing is submitted to it, no register is
read out of it, no batch is created by a caller. What HTTP this service serves
is Spring Boot Actuator and the **operations API** defined below, which does
what an operator could already do and nothing else.

Its contracts are:

- **Inbound** — the message on `yotresultsdistribution.requests`:
  `{ source, requestId, hearingId, hearingDay, sharedTime, eventType,
  userId? }` (`userId` optional; absent, never null). Agreed jointly with
  `cpp-context-results` (the publisher); changes are a cross-team event.
- **The register document** — the `courtRegisterDocument/*` schemas compiled
  at `criminal-court-public-model` **17.103.13**, `additionalProperties:
  false`, vendored into this repo as the **frozen** contract and enforced at
  the write into the service's own **register store**. Since increment 002
  the document is recorded here, not POSTed to progression; the schema is
  kept frozen because it is what the PDF payload mapper was written against
  and what any future consumer of the store will read. Changing it is a
  contract change under this principle even though no other context now
  receives it.
- **The operations API** — `src/main/resources/yot-results-distribution-openapi.yaml`, the third
  contract this service **owns** and versions with the repo. It describes every
  `/operations/**` endpoint, its request body, its success shape and every
  bounded `reason` it can refuse under. It is a contract in the full sense of
  this principle: a contract test asserts the controllers against it, and it is
  read at runtime by `cp-audit-filter-springboot` to resolve path parameters, so
  an endpoint missing from it is an endpoint whose audit event is wrong. It is
  **not** a business API (see the clause below), and adding a path that is not a
  named operator action to it is the amendment this principle exists to require.
- **Consumed platform contracts** — owned elsewhere, adapted to here, never
  redefined here:
  - systemdocgenerator `systemdocgenerator.generate-document` (REST command,
    **202 and nothing else is success**) and its public events
    `public.systemdocgenerator.events.document-available` /
    `generation-failed` on the Artemis `public.event` topic;
  - notificationnotify `notificationnotify.send-email-notification` (REST
    command, 202 only), attachment by file-service `fileId`;
  - the framework file-service `metadata` + `content` table schema
    (write-only, pinned to liquibase changesets 001–006), used to place the
    PDF payload where systemdocgenerator reads it;
  - the Azure App Configuration feature flag `YotResultsDistributionService`, read
    fail-closed, the same flag the results producer and the legacy triggers
    read — **the one lever** that decides which implementation is live.

Rules:

- Every contract MUST be documented on the Confluence design page (the repo
  carries no design narrative); the inbound message and the register document
  MUST have JSON schemas, versioned with the repo, that contract tests assert
  against; the consumed contracts MUST be vendored (schemas, DDL) under
  `specs/*/contracts/` or `src/*/resources/contracts/` with provenance, and
  adapter tests MUST assert against those copies.
- A change to the inbound contract or the register document is a
  **cross-team event**: a spec, an agreed change with the owning or consuming
  context, and a compatibility plan (assume the old shape is in flight).
  A change in a consumed platform contract is **their** event; this service
  detects it (a schema-asserting adapter test, a pinned DDL) and adapts.
- **A defect fix that changes the shape or population of a register-document
  component is still a cross-team event** in spirit: the Confluence design and
  the DEFECT-FIXES row MUST record it, and where the change is visible on the
  rendered PDF it carries the sign-off-before-cutover marker.
- **One lever.** No configuration value, static-data patch or endpoint MUST
  ever be introduced that decides, independently of the
  `YotResultsDistributionService` flag, whether this service or the legacy generates
  registers. Every failure to read the flag MUST leave the legacy in charge.
  An operations endpoint that merely lets a person do, over HTTP and under
  their own name, what a `kubectl exec` command already did is **not** a second
  lever: it reads the same flag, at the same point, and refuses the same way.
  An endpoint that switched which implementation is live, or that could run the
  generation leg without the flag having been read at all, **is** one and is
  forbidden.
- **The operations API.** Exactly one HTTP surface besides Actuator is
  permitted: the named operator actions under `/operations/**` that replace the
  operations CLI, one endpoint per action, adding no capability the CLI did not
  have. It is permitted only while **every** endpoint satisfies all four of:
  - **(a) Authorised.** Behind `cp-auth-rules-filter`, with an explicit allow
    rule in `src/main/resources/acl/` naming the caller groups admitted —
    currently the usersgroups group "Second Line Support" and no other, with
    identity taken from the `CJSCPPUID` header. There is no default-allow: an
    action with no rule is refused, and a rule that names no group is a bug.
    The filter MUST be enabled **by default** — `authz.http.enabled` reads
    `true` in this service's own configuration and in every deployed values
    file, against a library default of off. Switching it off is a deliberate
    configuration act of the operator, recorded in that environment's
    configuration; it is never a code default and is never inferred from the
    environment the service happens to be running in.
  - **(b) Audited.** Behind `cp-audit-filter-springboot`, so every request and
    every response is published as an audit event to the audit context. An
    endpoint that is reachable without an audit event is worse than the
    `kubectl exec` it replaced, which at least left a cluster audit record.
    The publisher MUST be enabled **by default** on the same terms as (a), and
    it takes **two** keys rather than one: `audit.http.enabled` reads `true` in
    this service's own configuration, and `cp.audit.enabled` — the transport,
    inside whose `@AutoConfiguration` class every `audit.http.*` bean the
    starter declares is built — reads `true` in every deployed values file,
    with the broker's connection from Key Vault. This service ships the
    transport key `false` so that a local run with no audit broker starts, so a
    values file that omits it serves the operations API **unaudited** and has
    not met this condition. Switching either off is a deliberate configuration
    act of the operator, recorded in that environment's configuration — never a
    code default, never inferred from the environment.
  - **(c) Flag-gated at least as strictly as its command was.** An endpoint
    reads the `YotResultsDistributionService` flag where the CLI command it replaces
    read it, and MUST NOT read it more permissively or omit the read the
    command made. It MAY be gated **more** strictly than its command, and MUST
    be where being reachable over HTTP turns an unconditional mutation into a
    second lever — a stricter gate is stated in the increment's spec and pinned
    by a test, never improvised. An override is an operator decision, is
    available on **one** endpoint only (the regeneration break-glass the CLI's
    `--ignore-flag` already was), and MUST be recorded in the audit event
    **and** on the run report. An endpoint that quietly bypasses the flag, or
    that mutates register state irrespective of it, is a second lever under the
    bullet above.
  - **(d) Telemetry-clean.** Every response — success and refusal alike —
    carries bounded codes, counts and identifiers only, under Principle VII. No
    defendant detail at all, recipient addresses masked as the CLI masked them,
    no exception text, no fragment of a store's or a far end's own words, and
    **no operator input echoed back**: a refusal names the argument, never the
    value that was typed.

  **How (a) and (b) are carried** (amended 2026-09-20, superseding the start-up
  refusal 4.1.0 described). By the **defaults above and nothing else** — (a) by
  `authz.http.enabled` in this service's configuration, (b) by
  `audit.http.enabled` there and `cp.audit.enabled` in the deployed values
  file, which is the half of (b) no code default can carry. Where the filter is
  on over a transport that is off, the service **says so** at start-up, at WARN,
  naming both settings: the filter that would have published is never
  constructed and the one that is swallows its own publishing failures, so an
  unaudited pod that said nothing would look exactly like an audited one. The
  service MUST NOT refuse to start on the combination of the operations API
  being enabled and either filter being off, and MUST NOT decide such a rule
  from a discriminator such as `yotresultsdistribution.servicebus.namespace`: a pod
  configured that way comes up and serves what it was configured to serve. An
  operator who turns a filter off has said what they meant, their environment's
  configuration records it, and a service that will not start on a choice its
  operator made is a service that cannot be operated. What a deployment review
  checks is that no deployed values file turns either switch off — not that the
  pod would have refused if one had. Conditions (c) and (d) remain
  unconditional, because they are this service's own code rather than a library
  it has to be given, and what a **value** may say is still refused at start-up:
  an audit transport switched on with no host or an impossible port, and an
  audit filter switched on with no OpenAPI document to resolve, are refusals
  about a value that cannot mean what it says, not about one switch given
  another.

  A new endpoint that is not one of those actions, or an existing one that
  stops satisfying (a)–(d), requires a constitution amendment and not just a
  spec. Actuator is unchanged and is not part of this surface.

**Rationale**: the queue message, the register document and the platform
commands are the whole *business* surface. Treating them with the discipline
other services give an OpenAPI spec is what keeps a redeploy on any side from
silently dropping registers — and treating the flag as a contract is what
keeps the rollback to a single action.

The operations API is the estate's answer to a narrower question: who may
regenerate a date, and can anybody tell afterwards that they did. A command
reached by `kubectl exec` runs as the pod, is available to anyone with exec
rights on the namespace, and leaves no record naming a person. The same action
behind `cp-auth-rules-filter` and `cp-audit-filter-springboot` is available to a
named group and leaves an audit event for every call — which is why the
surface is permitted at all, and why the four conditions are the permission
rather than a recommendation attached to it.

### IV. Canonical JSON In, Typed Models Out (NON-NEGOTIABLE)

Inbound hearing payloads (from Redis, or the results-query-api fallback) are
large, sparsely populated, and owned elsewhere. They MUST be handled
end-to-end as the JSON tree model (`JsonNode`) of the Jackson generation the
platform (Spring Boot) provides — currently Jackson 3
(`tools.jackson.databind.JsonNode`) under Spring Boot 4.1:

- No POJO/record mapping of the inbound hearing payload. Unknown fields MUST
  survive untouched; binding to a typed model silently discards what it does
  not know, and this service is not the owner of that shape.
- Jackson MUST be configured with the platform equivalent of
  `USE_BIG_DECIMAL_FOR_FLOATS` so monetary and numeric values round-trip
  exactly — no binary-float drift into a register.
- Inbound trees are **immutable in practice**: never mutate a `JsonNode` you
  did not construct. Derive new nodes; do not edit inputs in place. (The
  legacy pipeline mutates its hearing object in place and is saved only by
  the Durable Functions serialisation boundary; the port does not get that
  accident, so immutability is the rule that replaces it.)
- Output is the opposite: everything this service *produces* — the register
  fragment, the outbound aggregation, the `add-court-register` payload, the
  processed-log rows — MUST be typed Java records, **validated against the
  vendored progression schemas before submission** (this validation is
  itself defect fix C29: a schema-invalid document is an explicit FAILED,
  never a swallowed 400).

**Rationale**: fidelity in, contract-checking out. `JsonNode` guarantees we
cannot lose a field we did not anticipate; typed records validated against
the frozen contract guarantee we cannot send a document progression will
reject — and cannot lose a hearing's register without a trace when we would.

### V. SOLID with Ports and Adapters (NON-NEGOTIABLE)

The pipeline is expressed as an application core surrounded by adapters:

```
ASB listener (adapter)
    → DistributionPipeline (application core)
        → IdempotencyGuard         (processed-log port)
        → HearingPayloadSource     (Redis adapter; results-query-api fallback)
        → NowSubscriptionsSource   (reference-data adapter)
        → RegisterTransformer      (pure, no I/O — fragment, matching, mapping)
        → RegisterSubmissionClient (progression add-court-register adapter)
```

- Transport, payload source, reference data, and submission MUST each sit
  behind an interface owned by the core. The core MUST NOT import Azure
  Service Bus, Redis, HTTP client, or JDBC types.
- The transformation stage MUST be pure: JSON in, typed documents out, no
  I/O, no clock, no randomness (inject any of those). It is where all but a
  handful of the 34 fixes live, and it MUST be testable with golden files
  alone.
- Dependency injection MUST be constructor parameters with `private final`
  fields (explicit constructor or Lombok `@RequiredArgsConstructor`).
  Field-level `@Autowired` is forbidden, including in tests.
- Stubbed adapters are a legitimate, temporary state. A stub MUST implement
  the real port interface, MUST log at a level that makes its no-op-ness
  obvious, and MUST NOT be reachable in a production profile once the real
  adapter lands.

**Rationale**: the fix work is in the transformation; the risk is in the
adapters. Separating them lets the golden-file suite run in milliseconds with
no broker, no cache, and no Progression context, and lets each adapter be
replaced without reopening ported logic.

### VI. Explicit Failure — Nothing Is Ever Swallowed (NON-NEGOTIABLE)

**No exception is ever caught and ignored.** Not "for robustness", not "to
keep the consumer alive", not in a `finally`, not in a stub.

Every failure path MUST terminate in one of exactly two outcomes:

1. **Retry (abandon)** — a transient failure (connect, IO, 5xx, 429) retried
   with backoff, or the message abandoned so the broker redelivers it.
2. **Dead-letter** — a poison or exhausted message explicitly dead-lettered
   with a reason and description, visible on the DLQ.

**Alerting is required in addition, never instead.** Whichever of the two
outcomes is taken, the failure MUST also produce an ERROR log carrying
`requestId` and `hearingId` and increment a metric an alert fires on (DLQ
depth > 0; failures sustained 15 minutes). An alert is not a way of settling
a message; a message that is only alerted about has not been settled at all.

"Nothing to publish" is a state, not silence: the four legitimate no-op
outcomes — `group-proceedings`, `no-defendants`, `no-subscriptions`,
`no-youth-defendants` — are recorded as `COMPLETED` with a bounded
`completion_reason`, mutually distinguishable in the processed log. Two of
them are this flow's most common results; an undifferentiated success is the
legacy defect (C33), not an acceptable simplification.

Specific rules:

- The ASB consumer uses peek-lock with **explicit** `complete` / `abandon` /
  `deadLetter`. Auto-complete is forbidden. A message is completed only after
  the work it represents is durably done.
- `catch` blocks MUST rethrow, wrap-and-rethrow, or perform one of the two
  outcomes above. An empty `catch`, a `catch` whose body is only a `debug`/
  `trace` log, and a swallowed `InterruptedException` (without restoring the
  interrupt flag) are all build-blocking.
- **Service Bus health MUST NOT gate readiness.** A broker blip must not roll
  the pods; queue health is an alerting concern, surfaced as its own metric
  and a non-readiness health group.
- `System.out`, `System.err`, and `printStackTrace()` are forbidden in
  production code and tests; diagnostics go through SLF4J.

**Rationale**: silent failure is the disease this service was commissioned to
cure. The legacy app swallows the final POST's errors (C1), reports success
from four silent guards (C33), and loses whole registers on a 400 with no
trace (C29). A loud, retried, dead-lettered failure is a success of this
design; a quiet one is the only true outage.

### VII. Privacy in Telemetry (NON-NEGOTIABLE)

Logs, metrics, traces, and exception messages MUST NOT carry defendant
personal data at `INFO` level or above — no names, dates of birth, addresses,
NINOs, contact details, or free-text that may contain them. **Every defendant
on this register is a youth**; treat that as raising the stakes, not as a
nuance.

- The permitted correlation set at `INFO` is: `requestId`, `hearingId`,
  `hearingDay`, `source`, court-centre id / OU code, counts, and timings.
  Every log line about processing MUST carry the correlation of the unit of
  work it belongs to. For a delivery that is `requestId` and `hearingId`. A
  **scheduled run** has neither and cannot: it is one unit of work across many
  hearings and many batches, so it carries a `runId` of its own, minted per run
  and cleared when the run ends. A line about one batch carries its batch id.
  The rule is that no line is unattributable, not that every line names a
  delivery.
- Whole payloads, register fragments, and outbound documents MUST NOT be
  logged at any level in a deployed environment. Where a payload dump is
  genuinely needed for local diagnosis it goes behind `DEBUG` **and** an
  explicit local-only profile guard.
- Metric labels and dimensions are logs too: never label a metric with
  anything identifying a person.
- `completion_reason` and `failure_reason` are bounded codes — never raw
  exception text, never a fragment of the message body.
- Secrets, connection strings, and tokens MUST NOT appear anywhere in output.

**Rationale**: this pipeline handles criminal-court results for named
children across the whole estate's log shipping. Correlation IDs are enough
to debug it; personal data in a log index is an incident.

### VIII. Estate Conventions (NON-NEGOTIABLE)

- **Build**: Gradle (wrapper committed). Maven is forbidden.
- **Static analysis**: every analysis runs in `check`, and therefore in
  `build`; none of them has to be remembered on a command line. **PMD**, pinned
  (`toolVersion = "7.22.0"` in `gradle/pmd.gradle`) so a toolchain bump cannot
  change a verdict without a commit saying so, with `ignoreFailures = false`:
  `pmdMain` against `.github/pmd-ruleset.xml` and `pmdTest` against
  `.github/pmd-test-ruleset.xml` — the same base ruleset minus the rules that
  are inapplicable to test code, each exclusion carrying its reason in the file.
  **Checkstyle**: `google_checks` via `config/checkstyle/google_checks.xml`,
  `maxWarnings = 0`, over main **and** test sources; `checkstyleTest` reads
  `config/checkstyle/checkstyle-suppressions.xml` through `configProperties`,
  which suppresses `MethodName` over `src/test` and nothing else — the test
  naming convention is underscore-separated by design. **Coverage gate**:
  `jacocoTestCoverageVerification` in `check` — LINE ≥ 0.88, BRANCH ≥ 0.85,
  excluding the application entry point and `config/**`. It `mustRunAfter`
  `jacocoTestReport`, so a build that fails the gate still leaves a report
  naming the lines that were missed. Thresholds are a ratchet, never loosened
  in passing, and that is enforced rather than trusted: a pull request lowering
  or deleting a `minimum` in `gradle/test.gradle` is failed by the Test job's
  coverage ratchet guard, which names the before and after values. Warnings are
  not tolerated as normal. Suppressions MUST be inline (or, where the tool has
  no inline form, in the named ruleset/suppressions file), narrow, and carry a
  reason.
- **Package root**: `uk.gov.hmcts.cp`; this service's code lives under
  `uk.gov.hmcts.cp.yotresultsdistribution`.
- **Commits**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`,
  `refactor:`, `test:`, `build:`, `ci:`, `style:`). `build` covers dependency
  and local-tooling changes (dependabot raises them as `build(deps)`), `ci` the
  workflows, `style` formatting-only changes that leave behaviour untouched.
- **Branches**: work lands on `main`; no ticket prefixes (user decision,
  2026-08-31 — there is no Jira ticket for this increment). If a ticketed
  workflow arrives later, branch naming is revisited by PATCH amendment.
- **No AI attribution anywhere** — not in commit messages, branch names, PR
  titles or descriptions, code comments, or documentation. No
  `Co-Authored-By` trailers naming a tool, no generated-with footers. All
  output reads as developer-authored work.
- Logging is SLF4J + Logback. `src/main/resources/logback.xml` configures a
  single console appender using `LoggingEventCompositeJsonEncoder`
  (`net.logstash.logback`), so JSON is emitted **unconditionally** — there is
  no `json` Spring profile and no plain-text alternative. MDC is a declared
  provider, which is what puts `requestId` and `hearingId` on every line
  (Principle VII).

**Rationale**: this repo is one of ~70 in the CPP estate and is operated by
people who did not write it. Uniform build, analysis, naming, and history let
them read it the same way they read everything else.

## Technology Stack & Deployment

- **Java**: 25. **Framework**: Spring Boot 4.1, from
  `hmcts/service-hmcts-crime-springboot-template`.
- **Ports**: local `8082`; Kubernetes `4550`.
- **Messaging**: Azure Service Bus queue `yotresultsdistribution.requests` + its
  dead-letter queue, consumed via `azure-messaging-servicebus`
  `ServiceBusProcessorClient`.
  - Peek-lock, explicit settlement, **`maxDeliveryCount` 5**.
  - Broker **duplicate detection on**; `messageId` = `source:requestId`.
  - **Replay tooling MUST always mint a fresh `messageId`** — deliberate
    replay must not be silently discarded by duplicate detection.
  - `maxConcurrentCalls` starts at 2, matching the AKS fleet's pinned Durable
    Functions throttle.
- **Idempotency**: PostgreSQL processed-log — `processed_request` keyed
  `(source, request_id)`, plus `processed_output` with
  **`UNIQUE (source, request_id)`**: the court register produces exactly one
  document per hearing, so the output cardinality is 0..1 and there is no
  fan-out dimension (the informant's per-authority key does not apply).
  Schema migrations via **Flyway**. Since increment 002 the output is a
  **local transaction** into the register store: `processed_output` carries
  the validated document and a re-share of the same hearing on the same
  register date supersedes the earlier row **at the write**, so the guarantee
  is exactly-once recording per command. Downstream, the batch's
  `payload_file_id` and each recipient's `notification_id` are minted and
  persisted **before** the call that uses them, so a retried
  `generate-document` re-renders the same payload and a retried
  `send-email-notification` reaches the same notification aggregate: an
  ambiguous downstream outcome is therefore **retried** — a possible
  duplicate is absorbed by construction, a possible loss is silent.
- **Payload source**: Redis `INT_` claim-check (dated key form first, then
  the legacy undated twin) with a results-query-api fallback; verified TLS on
  both (fix C15). A cache miss AND fallback miss is a recorded transient
  failure, never a silent stop (fix C32).
- **Outbound (per command)**: the document is validated against the vendored
  `courtRegisterDocument/*` schemas (fix C29) and **recorded** in
  `processed_output` with completion reason `recorded`. The 001 POST to
  progression is retained only behind `yotresultsdistribution.output=progression-post`
  for the documented fallback sequencing; the default is `record`.
- **Outbound (per batch)**: a service-owned job at **18:00 Europe/London,
  Mon–Fri** (explicit zone, validated at startup; **ShedLock** so one run
  proceeds across instances) reads the `YotResultsDistributionService` flag once, no
  cache, and skips when OFF or unreadable; otherwise it groups active
  records by (court centre, register date), maps each batch to the PDF
  payload progression's `CourtRegisterPdfPayloadGenerator` produced
  (bug-for-bug, `####` → newline kept), inserts the payload into the shared
  file-service database (write-only role), POSTs `generate-document`
  (`OEE_Layout5`, `originatingSource = YotResultsDistributionService`,
  `sourceCorrelationId = batch_id`; 202 only), and awaits the outcome.
- **Events**: a durable JMS subscription to the Artemis `public.event` topic
  with a `CPPNAME` selector for `document-available` / `generation-failed`,
  filtered on the service's own `originatingSource`, correlated on
  `sourceCorrelationId`. A batch that neither event reaches is not guessed at:
  the next run's **release pass** is its first act, failing every PENDING or
  GENERATING batch past its cutoff `NOT_COMPLETED_BY_NEXT_RUN` and releasing
  its rows into that run's batches, one fenced statement per batch.
- **Notification**: one `send-email-notification` (202 only) per distinct
  recipient in the de-duplicated union of the batch's records' recipients,
  `fileId = documentFileServiceId`, `personalisation.yotsName`; per-recipient
  outcome recorded; batch state NOTIFIED / PARTIALLY_NOTIFIED /
  NOTIFIED_NOBODY. The `cr_standard` template id is required at startup.
- **Second datasource**: the shared framework `fileservice` Postgres,
  `INSERT` on `metadata` and `content` only, credentials via Key Vault CSI; a
  readiness input only while a run is in progress.
- **Feature flag**: `com.azure:azure-data-appconfiguration`, authorised by the
  estate's shared App Configuration connection string
  (`APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING`, from Key Vault via the CSI
  driver - the secret resultsvalidator reads), key
  `.appconfig.featureflag/YotResultsDistributionService`, label = stack, 2 s timeout.
  The connection string is never committed, never defaulted, and never reaches
  a log, an exception message, a `toString()` or a response (Principle VII).
- **HTTP surface**: Spring Boot Actuator — health, readiness/liveness,
  metrics — **and** the operations API under `/operations/**`. No business
  endpoints (Principle III). Since increment 005 the seven operator actions are
  endpoints rather than commands in the image: read the flag, list a date's
  batches, list what was recorded while the flag was off, regenerate a date,
  re-notify a batch, supersede what was recorded before an instant, and pull
  the exception report. Every one of them is behind `cp-auth-rules-filter`
  (drools rules under `src/main/resources/acl/`, identity from `CJSCPPUID`,
  "Second Line Support" only) and `cp-audit-filter-springboot` (every request
  and response published to the audit context), and is described in
  `src/main/resources/yot-results-distribution-openapi.yaml`. The CLI (`batch/cli/`, the
  `yotresultsdistribution.cli` property and the `docker/startup.sh` dispatch) is
  **removed**: the image starts the application, full stop.
- **Test stack**: JUnit Jupiter 6 (the Boot 4.1 test starter) + Mockito
  (unit); golden-file/fixture tests for the ported transformation;
  **Testcontainers** — Service Bus emulator and PostgreSQL — for integration
  tests (suffix `*IT`); **WireMock** for the progression, results-query and
  reference-data stubs.
- **Observability**: structured JSON logs with `requestId`/`hearingId`;
  metrics for processed/failed, the `completion_reason` distribution, and
  queue + DLQ depth; alerts on DLQ > 0 and on failures sustained for
  15 minutes. Expect a high COMPLETED-but-not-submitted rate: two of the four
  no-op reasons are this flow's most common legitimate outcomes.
- **Secrets/identity**: workload identity + Key Vault CSI. The App
  Configuration connection string is the one static key this service holds, and
  it arrives the same way every other secret does - Key Vault, CSI, never a
  committed value or an environment default. The one connection string that is
  committed, the published local pair in `docker-compose.yml`, authorises
  nothing and is refused wherever a real flag is read (006 FR-005).
- **Deployment**: AKS via the standard Flux route. Since increment 002 this
  service **owns the schedule, the render request and the e-mail fan-out**;
  it contains **no PDF rendering code** (systemdocgenerator renders the
  unchanged template) and **no GOV.UK Notify client** (notificationnotify
  sends). Progression's court-register leg stays deployed and idle through
  cutover and soak and is retired by a separate change in that repository.
- **Out of scope by construction**: the prison court register (its own
  pipeline and future migration), SJP hearings (the court register has no SJP
  leg), the legacy function-app repo itself (C18/C28/C34 are registered as
  pending items owned elsewhere), and the progression retirement PR
  (progression's repository, after soak).

### Increments

- **001 "court-register-port" — complete.** The full pipeline port with the
  31 in-service defect fixes landed (C18/C28/C34 tracked externally to
  conclusion before cutover): ASB consumer, idempotency guard, the ported
  transformation (fragment build, subscription matching, the 12-mapper
  aggregation), the Redis + results-query payload adapter, the reference-data
  adapter, and the progression submission adapter — finished by the
  differential audit against the legacy oracle (381 runs, zero unattributed
  differences).
- **002 "consolidate-progression-leg" — complete.** Record instead of POST
  with write-time supersession; the 18:00 Europe/London flag-gated batch job;
  `DefendantTypeResolver` and `PdfPayloadMapper` ported Java→Java from
  progression with goldens recorded from progression's classes; the
  file-service, systemdocgenerator, notificationnotify and App Configuration
  adapters; the `public.event` listener with the grace-period reconciler it
  shipped with, since replaced by 004's release pass; the
  operations CLI; the progression-leg defects appended to the register as
  `P1`–`P9` (six FIXED, two RETIRED, one MOOT). Every phase is built
  test-first under Principle II; every fix lands with its DEFECT-FIXES row
  under Principle I.
- **003 "exception-report" — complete.** The capability design section 11
  promised and neither half ever had: a 07:00 Europe/London weekday run, on a
  scheduler and a lock of its own, reporting every FAILED request, every request
  still in flight past its threshold, every batch late at one of its three
  stages, every failed batch and every refused notification over the window that
  opens at the previous scheduled run. Two sinks — the structured events Log
  Analytics indexes, and the CSV written into the file service and e-mailed to
  support, one send per address; `IntakeAgeSweep`'s two intake gauges on their
  own fixed delay in every non-command JVM and under no lock; the
  request-duration timer and the report's own counters, which complete the four
  instruments; and `report-exceptions`, the sixth operations command. It is not
  on the cutover lever's circuit: it reads the flag nowhere and runs whatever
  `yotresultsdistribution.generation.enabled` says. **No `doc/DEFECT-FIXES.md` row is
  added or amended** — there is no legacy oracle for a capability that was never
  built, and a new capability is not a deviation from one (Principle I). The
  e-mail output ships switched off in every environment until the
  notificationnotify team provides the template it is sent under.
- **004 "release-stale-batches" — complete.** The grace-period reconciler and
  its systemdocgenerator document query are removed, and what replaces them is
  the 18:00 run's **first act**: every PENDING or GENERATING batch past its
  cutoff is failed `NOT_COMPLETED_BY_NEXT_RUN` and its registers released into
  that same run's batches, one fenced statement per batch so that two replicas
  cannot both release one. `GENERATION_TIMED_OUT` goes with the mechanism that
  produced it; `BatchAgeSweep` publishes the three in-flight batch ages the
  pass leaves with no other reader, because a Micrometer gauge never decays and
  a reading nobody refreshes goes on looking live; and the run line gains
  `released_batches`, `released_registers` and `contended`. A batch nothing was
  learned about still has nothing invented about it - it is failed through the
  store rather than through the sink. **No `doc/DEFECT-FIXES.md` row is added
  or amended.**
- **005 "operations-rest-api" — complete.** The six operations commands become
  seven `/operations/**` endpoints and the CLI is removed. The REST layer is an
  inbound adapter in `uk.gov.hmcts.cp.yotresultsdistribution.api` that calls the same
  application services the CLI classes called, moving no logic and adding no
  capability: the same arguments, the same refusals, the same output fields as
  JSON, and the CLI's three exit codes mapped onto status codes (0 → 2xx;
  refused → 409, or 400 for an argument that will not read; failed → 500), each
  refusal carrying the bounded `reason` the command printed. Authorisation and
  audit come from the estate starters under Principle III(a) and (b), the
  OpenAPI document joins the owned contracts, and `POST
  /operations/batches/generate` refuses `SCHEDULE_RUNNING` while the 18:00 run
  holds its lock — a rule the CLI left to a runbook and the API has to check,
  because an endpoint is reachable by more people than an exec was. **This
  increment must not be deployed to STE before the ingress route for
  `/operations/**`, the usersgroups path for the identity client and the
  Artemis audit connection land in the infrastructure repositories**: the CLI
  is gone, so a pod deployed without them has no operational surface at all.
- **006 "appconfig-connection-string" — current.** The cutover flag is read
  with the estate's shared App Configuration connection string from Key Vault
  (`yotresultsdistribution.feature.connection-string`) instead of the pod's
  workload identity, which no App Configuration role can be assigned to through
  `ccm-namespace`; `feature.endpoint` and `feature.credential` are removed. The
  one lever is unchanged - same key, same label, read once per run, no cache,
  fail-closed. The string is refused at start-up wherever it is set and cannot
  be parsed, and is never quoted in a refusal, a log line or a rendering of the
  settings. **No `doc/DEFECT-FIXES.md` row is added or amended.**

## Development Workflow & Quality Gates

- The contract artefacts (inbound message schema, the vendored progression
  schemas, the Confluence design page) MUST be updated **before** any code
  change that affects a contract (Principle III).
- Every feature built via spec-kit lives under `specs/NNN-slug/` containing
  at least `spec.md`, `plan.md`, and `tasks.md`. Flow:
  `/speckit-specify → /speckit-plan → /speckit-tasks → /speckit-implement
  → /speckit-analyze`.
- Non-trivial changes flow through `Spec → Write → Code Review → QA →
  Spec-Validate → Fix → Ship`. The reviewer agents (`code-reviewer`, `qa`,
  `spec-validator`) report findings only; they MUST NOT modify code. The
  primary agent or a human applies fixes and re-runs until all three return
  PASS / COMPLIANT. Exempt: markdown-only edits, whitespace/import-only
  edits, and `.claude/rules/*` or `CLAUDE.md` updates.
- Required to run cleanly before merge:
  - `./gradlew build` — compilation, the full test suite, **PMD** (main and
    test), **Checkstyle** (main and test) and the **JaCoCo coverage
    verification**. Every analysis runs in `check`; there is no longer an
    analysis `build` skips, and none of them needs naming separately.
  - `./gradlew test` — the whole suite; there is no separate
    `integrationTest` task, so the Testcontainers suites run here and need
    Docker only when those tests are in the selection.
  - `./gradlew jacocoTestReport check` — the order CI uses, so the coverage
    report exists to be read whichever way the gate goes.
- Any change to ported logic MUST run the golden-file suite; **a golden file
  is only updated in the same commit as a `doc/DEFECT-FIXES.md` entry (new
  or amended)** — the fix register is what authorises a golden to move
  (Principle I).
- Pull requests: the description MUST state which principle(s) the change
  touches. Any deviation requires explicit written justification in the PR
  description and MUST be flagged in the plan's "Complexity Tracking"
  section.
- Reviewers MUST specifically look for: a swallowed exception
  (Principle VI), an auto-completed message, a typed model bound over an
  inbound payload (Principle IV), PII in a log line (Principle VII),
  production code with no preceding failing test (Principle II), **a
  catalogued defect reproduced instead of fixed, and a fix with no
  DEFECT-FIXES row or no pinning test** (Principle I).

## Governance

This constitution supersedes the informal conventions in `.claude/rules/`
and the template-derived guidance in `CLAUDE.md` — including any API-first
rule, which applies to this service only through Principle III's operations
API and never to a business endpoint, of which there are none. Where
this document and those files disagree, this document wins; they are
retained as quick-reference material and MUST be kept in sync.

**Amendment procedure**:

1. Propose the change in a feature spec under `specs/`.
2. Bump `Version` per semantic versioning:
   - **MAJOR** — a breaking principle change, removal, or redefinition that
     invalidates existing practice.
   - **MINOR** — a new principle, new section, or materially expanded
     guidance.
   - **PATCH** — clarifications, wording, typo fixes, or non-semantic
     refinements.
3. Re-run `/speckit-analyze` on every in-flight feature spec to verify it
   still aligns with the amended principles; update or waive as required.

**Compliance expectations**:

- All PRs MUST honour these principles.
- Deviations MUST be explicitly justified in the PR description and, where
  relevant, in the plan's "Complexity Tracking" table.
- Reviewers MUST block merges that silently violate a NON-NEGOTIABLE
  principle without a written waiver.
- Behaviour relative to the legacy function app is governed by the
  **defect-fix register** (`doc/DEFECT-FIXES.md`, Principle I), not by PR
  discussion alone. The polarity is: **a catalogued fix merged without a
  register entry is the defect** and MUST be reverted or registered
  retrospectively with named approval; an **uncatalogued** behaviour change
  needs the same written sign-off the old parity regime demanded, before
  merge. C-numbers are stable: renumber never, append only.

**Version**: 5.1.0 | **Ratified**: 2026-08-31 | **Last Amended**: 2026-09-29
