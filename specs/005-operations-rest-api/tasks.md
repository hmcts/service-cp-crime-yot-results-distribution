# Tasks: Operations REST API, replacing the operations CLI

**Input**: design documents from `/specs/005-operations-rest-api/`
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`

**Tests are MANDATORY** (constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix in the same commit.

**Red-run convention (every test task)**: the test task includes the minimal **compile-safe seams**
its test needs — interface declarations, record signatures, class skeletons whose methods throw
`UnsupportedOperationException` — so the recorded red run is a **failing assertion**, never a
missing class or a compile error. The failing assertion is quoted in the test task's commit
narrative; the paired implementation task's narrative quotes the green run.

**[A] tasks** are acceptance/characterisation: they verify assembled behaviour and record the
observed result rather than a red run. No implementation task follows one.

### Approved TDD exceptions

**None in advance.** The constitution's exemption is narrow — "pure mechanical refactors (rename,
move, extract with no behaviour change), formatting, and comment-only edits" — and this increment
claims it for **one** task kind only: the deletion tasks of Phase 10, where a deletion has no red
run and its evidence is that the suite the deleted code was proven by is gone with it and the build
is green.
**It is not claimed for Phase 1's dependency and wiring tasks.** An earlier draft of this file did,
and a gate reviewer was right that adding a dependency, an exclude filter and a settings block is
not a mechanical refactor. T002/T003 is an ordinary red/green pair whose red is a failing assertion
(see the entry against T002), as T004/T005 and T006/T007 are. Everything else is a pair. If a pair
cannot be formed, the exception is written into **this section** with the design owner's dated
approval **before** the commit lands — never argued for afterwards in a commit body.

### Two standing rules for this increment

1. **No `doc/DEFECT-FIXES.md` row is added, amended or flipped anywhere.** There is no legacy oracle
   for an operational surface. `RegisteredDefectFixes` and `DifferentialAuditTest` are untouched and
   must stay green. A task that finds itself wanting a `C` or `P` number has found a defect in
   001–003, not in this increment, and it stops and asks.
2. **The coordination contract in `plan.md` is binding.** 005 must not touch
   `batch/RegisterGenerationJob`, `batch/GenerationReconciler`, `application/DocumentRenderer`,
   `adapter/systemdocgenerator/*`, the `yotresultsdistribution.generation.*` block of `application.yaml`, or
   README's generation section. Where a task is near one of those, it says so.

**Conventions**: package root `uk.gov.hmcts.cp.yotresultsdistribution`; production code under
`src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/`, tests under `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/`.
`*IT` suites need Docker and run inside `./gradlew test`. Conventional Commits on
`005-operations-rest-api`; accepted types `feat`, `fix`, `chore`, `docs`, `test`, `refactor`,
`build`, `ci`, `style`. No AI attribution in any commit, comment, document or test name. Every
`./gradlew` invocation goes behind the shared `flock`. Every phase ends with a green
`./gradlew build` and a review gate before the next phase starts. **Never two committing agents at
once.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no
  dependency on an unfinished task)
- **[A]**: acceptance/characterisation
- **[US#]**: the spec user story the task traces to

---

## Phase 0: Governance (1 task)

**Purpose**: close the constitution's own amendment procedure before any code lands. Step 1 (the
proposal) is in `spec.md`; step 2 (the bump) is commit `d73ef50`; step 3 is this.

- [x] **T001** [A] **Run `/speckit-analyze` for `specs/005-operations-rest-api/`, and again for the
      concurrent `specs/004-release-stale-batches/`** (in the main checkout —
      `/home/sachin/moj/service-cp-crime-yot-results-distribution`, **read-only**: never edit that tree from
      this branch). Governance step 3 requires every in-flight spec to be re-checked against the
      amended principles and each conflict updated or explicitly waived. Update this spec where the
      analysis finds a conflict; for 004, **record** each conflict in this task's commit narrative
      for the orchestrator to act on. The known one to look for: 004's run-report line and 005's
      operator trigger both touch `domain/RunReport` (`batch/` in an earlier draft of this task and
      of the plan; the class has always been in `domain/`), and 004's pre-batching pass must skip
      operator-initiated batches (plan, coordination contract). Evidence: the analyse output for
      both specs, and the conflict list.
      (**Re-run at gate round 2, for the 4.0.0 → 4.1.0 amendment.** The Governance procedure's step
      3 applies to every bump, and this one scoped the enforcement of Principle III's conditions (a)
      and (b), so the re-check was against those: FR-045 and FR-053 now read as *implementations* of
      a scope the constitution states rather than as a spec-level exemption from one; conditions (c)
      and (d) are unconditional in the spec and unaffected; FR-053 is carried by T004/T005, which
      have landed, and by T013, which now names the refusal waiting for its yaml block; FR-044's
      default and the "switch the endpoints off" escape are the same sentence on both sides; and
      `quickstart.md`'s Local section is what the exemption describes. Three drifts found and
      closed in the same commit — the spec's amendment section named only the 4.0.0 proposal
      (Governance step 1 is per amendment), the plan's Constitution Check row named no version and
      no enforcement environment, and `design_rules`' operations-API section described the filters
      without saying they are a startup refusal. No conflict with 004: the amendment touches
      nothing 004 relies on. Metrics: 49 functional requirements, 12 success criteria, 60 tasks;
      0 ambiguities, 0 duplications, 0 critical issues.)
      (**Re-run at gate round 3, for the 4.1.0 → 5.0.0 amendment**, which relaxes conditions (a)
      and (b) from a start-up refusal to a secure default. Scoped to what the bump touches, as the
      two runs above were. Findings — the same drift in eight places, all closed on this branch in
      the two commits after the bump: `plan.md`'s Constitution Check row still names 4.1.0 and the
      refusal (**CRITICAL**, because a plan may not contradict the constitution); its settings
      table still carries `audit.http.enabled: ${HTTP_AUDIT_ENABLED:false}` against FR-045's `true`
      (HIGH); its source-structure note and its test-matrix row still describe the refusal and the
      discriminator's two "should start" counterparts (MEDIUM); risk 3 and the Complexity Tracking
      row still lean on the refusal as half of what closes condition (b) (HIGH); and in `tasks.md`
      T004/T005's narratives, T013's ⚠ note and T043's `${HTTP_AUDIT_ENABLED:false}` say the same
      (HIGH). `design_rules.md`'s operations bullet and `quickstart.md`'s Local section were closed
      in the bump's own commit.
      Coverage after the rewrite: FR-045 is carried by T004/T005 (the defaults, and the pod that
      starts with both switches off) and by T013/T043 (the two yaml blocks); FR-053 by T004/T005
      (the value-shape refusals, including the corrected port range) and T043. No requirement loses
      its task and no task loses its requirement.
      **No conflict with 004**: `specs/004-release-stale-batches` mentions neither filter, neither
      switch, Principle III nor a constitution version — grepped read-only in the main checkout,
      zero hits. Metrics: 49 functional requirements, 12 success criteria, 60 tasks; 0 ambiguities,
      0 duplications, 1 critical issue, closed by the plan rewrite two commits later.)
      (**Re-run at gate round 4, for the 5.0.0 → 5.0.1 clarification**, which names the audit
      transport key beside the HTTP filter's in condition (b). Scoped to what the bump touches, as
      the three runs above were. Findings — the same drift in four places, all closed in the three
      commits around the bump: `spec.md`'s FR-045, assumption 9 and deployment gate 5 said a
      deployment that says nothing is *audited* where the shipped `cp.audit.enabled` default makes
      it authorised and unaudited (HIGH); `plan.md`'s settings table read `cp.audit.enabled: true
      deployed` of a tree that ships `${CP_AUDIT_ENABLED:false}`, and its Constitution Check row
      named the two switch defaults as the whole of (b) (HIGH); `design_rules.md`'s operations
      bullet said the same (MEDIUM); and `application.yaml`'s own comment called the two switches
      "the whole of how conditions (a) and (b) are carried" (MEDIUM). FR-053's first bullet was
      corrected with them: it required the OpenAPI key "wherever `audit.http.enabled` is on", where
      the rule is — and must be — gated on both switches, because that is where the parser that
      globs is built (LOW, spec-validator).
      Coverage after the clarification: FR-045 is carried by T004/T005 (the defaults, the pod that
      starts with both switches off, and the start-up WARN that names the unaudited combination)
      and by T013/T043 (the two yaml blocks); FR-053 is unchanged. No requirement loses its task
      and no task loses its requirement.
      **No conflict with 004**: re-grepped read-only in the main checkout for either filter, either
      switch, Principle III and a constitution version. One hit, and it is not one:
      `specs/004-release-stale-batches/contracts/README.md:5` cites Principle III to say 004 has no
      inbound message, no REST surface and no event of its own — which the clarification leaves
      exactly as it was. Metrics: 49 functional requirements, 12 success criteria, 60 tasks; 0 ambiguities,
      0 duplications, 0 critical issues.)

---

## Phase 1: The two starters, the component scan, and the settings (7 tasks)

**Purpose**: get the dependencies onto the classpath **without the context failing**, and make every
start-up refusal that the amendment's condition (b) requires. Three of research's findings are
context-will-not-start traps and all three are closed here. All three pairs are ordinary red/green
pairs: T002/T003's red is an assertion about a context that would not refresh, not the refusal
itself (see T002), and T004/T005 and T006/T007 are refusals asserted the way every other startup
refusal in this repository is.

- [x] **T002** [US1] `config/AuditComponentScanTest` (new) — **the context starts, and holds no
      component-scanned audit bean**. An `ApplicationContextRunner` over the application's own
      configuration
      with the operations and audit switches off, asserting the context starts and that no bean of
      type `uk.gov.hmcts.cp.filter.audit.parser.OpenApiSpecificationParser` was created by scanning.
      Seam: none — the assertion is about the context that already exists.
      Red: passes today (there is no such dependency), and **fails the moment T003 adds it**, which
      is the point: the task order is test-then-dependency so the failure is observed rather than
      predicted. Record both runs.
      (run on this commit's tree: `./gradlew test --tests '...AuditComponentScanTest'` - 3 tests,
      0 failures, all three PASSED, the predicted green-today. The context refreshed in 2.064s on
      the `test` profile with `cp.audit.enabled=false`, `audit.http.enabled=false`,
      `authz.http.enabled=false` and `yotresultsdistribution.operations.enabled=false`; nothing on the
      classpath is in `uk.gov.hmcts.cp.filter.audit`, so `auditStarterBeans()` is empty for the
      only reason it can be today. The second of the three runs - the one that fails - is recorded
      against T003, which is where the dependency arrives. Deviation from the task text, additive:
      the assertion is written over the whole `uk.gov.hmcts.cp.filter.audit` package rather than
      over `OpenApiSpecificationParser` alone, and by **class name** rather than by type, because a
      `.class` literal would not compile in the state this suite is deliberately written in. The
      named parser keeps a case of its own.)
      **Re-recorded at gate round 1, and the reason is worth keeping.** The red quoted against T003
      below was a context *bootstrap* failure - the suite was `@SpringBootTest`, so a refusal to
      refresh reached JUnit as an initialisation error before any assertion ran, and this repository
      does not accept that as a red (constitution Principle II; the "mechanical exemption" claimed
      for this pair was wider than the constitution's, which covers a purely mechanical refactor).
      The suite is now written over an `ApplicationContextRunner` with
      `ConfigDataApplicationContextInitializer` - the same `Application` class, the same `test`
      profile, the same four switches - so a failed refresh is an object to assert on. With the
      exclusion taken back out of `Application`, the red is three **AssertionErrors**: *"Expecting
      <Unstarted application context ...> to have not failed: but context failed to start:
      UnsatisfiedDependencyException: Error creating bean with name 'openApiSpecificationParser'
      defined in URL [jar:...cp-audit-filter-springboot-1.0.5.jar!/.../OpenApiSpecificationParser
      .class]"*. Put back, 3 tests, 0 failures.
      One thing the runner needs that `@SpringBootTest` supplied: Boot's `TypeExcludeFilter` is a
      **delegating** filter, and the beans it delegates to are registered by the test bootstrapper,
      not by the runner - so without one, scanning `uk.gov.hmcts.cp` finds every other suite's
      nested `@Configuration` and the context dies on a duplicate `objectMapper`, which is the
      collision `Application`'s javadoc already describes arriving from the other direction. The
      suite registers that missing bean itself, excluding by the directory the class was read from
      (`/classes/java/test/`) rather than by a naming convention, so a support class not named after
      a suite is excluded like the suites are. It says nothing about the audit package.)
- [x] **T003** [US1] **Add the dependencies and close the component-scan clash.**
      `gradle/libs.versions.toml`: `cp-auth-rules-filter = "1.0.7"`,
      `cp-audit-filter-springboot = "1.0.5"`, plus the `uk.gov.hmcts.cp:` module lines;
      `build.gradle`: `implementation libs.cp.auth.rules.filter`,
      `implementation libs.cp.audit.filter.springboot`, and `spring-boot-starter-web` **if it is not
      already a first-class dependency** (check first; actuator alone will not map a controller).
      `src/main/java/uk/gov/hmcts/cp/Application.java`: the `@ComponentScan` exclude filter for
      `uk\\.gov\\.hmcts\\.cp\\.filter\\.audit\\..*`, verbatim from research R9 — `@AutoConfiguration`
      classes are not subject to `@ComponentScan` filters, so the auto-configuration still runs.
      `application.yaml`: `cp.audit.enabled: false` for the local and test profiles (research R6 —
      the library is **on** by default and validates its Artemis settings even with HTTP audit off).
      Green: T002 passes again with the dependency present. Evidence: the failing run between T002
      and the exclude filter, quoted.
      (red, with both starters on the classpath and no exclude filter: `AuditComponentScanTest`
      3 tests, 3 failed - *"Error creating bean with name 'openApiSpecificationParser' defined in
      URL [jar:.../cp-audit-filter-springboot-1.0.5.jar!/uk/gov/hmcts/cp/filter/audit/parser/
      OpenApiSpecificationParser.class]: Unsatisfied dependency expressed through constructor
      parameter 1: No qualifying bean of type 'java.lang.String' available"*, with
      `cp.audit.enabled=false` and `audit.http.enabled=false` set, which is the point: the scan
      answers to neither.
      Then a **second red the task did not predict**, and the one worth keeping: with the filter
      added verbatim from research R9 the context still failed, now on *"The bean 'objectMapper',
      defined in class path resource [.../ReportEmailConfigTest$EmailWiringTestConfiguration.class],
      could not be registered. A bean with that name has already been defined in class path
      resource [.../LiveNotificationConfigTest$NotificationTestConfiguration.class]"*. Declaring a
      `@ComponentScan` **replaces** the one `@SpringBootApplication` carries, and with it the
      `TypeExcludeFilter` that keeps one slice suite's nested `@Configuration` out of another's
      context. R9's snippet - and the reference implementation it is copied from - omits both of
      Boot's own filters; this repository's suites are what notice. Fixed by restating
      `TypeExcludeFilter` and `AutoConfigurationExcludeFilter` beside the audit regex, which is the
      documented recipe. Research R9 is left as the record of what the reference implementation
      does; the deviation is in `Application`'s javadoc, where somebody deleting a filter will read
      it.
      Green: `AuditComponentScanTest` 3 tests, 0 failures; then the whole suite behind the shared
      lock, `./gradlew build -Dtest.noFailFast=true` **exit 0** in 10m 35s - so neither starter
      disturbs the existing classpath (the audit library's Jackson 2 alongside this service's
      Jackson 3 was the risk, and it does not materialise).
      `spring-boot-starter-web` was already a first-class dependency, so it is not added; the two
      starters go in a new `gradle/libs.versions.toml` as the task says, which is not a breach of
      `build.gradle`'s "keep every dependency here" comment - that comment is about the
      `apply from:` files, which dependabot cannot read, and a version catalogue is one it can.)
- [x] **T004** [P] [US1] `config/OperationsPropertiesTest` (new) and `config/ConfigurationValidationTest`
      (extend) — **the defaults and the value refusals**. Defaults off an
      `ApplicationContextRunner`: `yotresultsdistribution.operations.enabled=true`, `supersede-max-age=30d`,
      `lock-wait=0s`, and off the shipped file `authz.http.enabled=true` and
      `audit.http.enabled=true`. Refusals, each asserting the message **names the offending
      setting**: `an_audit_transport_with_no_broker_refuses_to_start`,
      `an_audit_transport_whose_only_host_is_blank_refuses_to_start`,
      `an_audit_transport_whose_hosts_are_all_blank_refuses_to_start`,
      `an_audit_transport_with_no_port_refuses_to_start`,
      `an_audit_port_that_is_not_a_number_refuses_to_start`,
      `an_audit_port_above_the_tcp_range_refuses_to_start`, `an_absent_audit_port_refuses_to_start`,
      `an_audit_filter_with_no_openapi_spec_key_refuses_to_start`,
      `a_zero_supersede_max_age_refuses_to_start`, `a_negative_lock_wait_refuses_to_start` — with
      `the_highest_port_there_is_should_start`, `the_lowest_port_there_is_should_start`,
      `an_unconfigured_transport_that_is_switched_off_should_start`,
      `an_audit_filter_over_a_transport_that_is_off_should_start_without_a_spec_key` and
      `a_pod_with_the_whole_audit_path_configured_should_start` as their counterparts.
      And the three cases that say what is **not** refused:
      `a_pod_with_both_filter_switches_off_should_start`,
      `a_pod_serving_the_operations_api_unaudited_should_start`,
      `a_pod_serving_the_operations_api_unauthorised_should_start`.
      Seams: `config/OperationsProperties` declared with its components and **no** `@DefaultValue`s;
      `PropertiesValidator` gains a package-private `validateOperations(...)` that returns without
      looking.
      (red, first landing: 166 tests, 6 failures, 0 errors, every one an assertion. The refusals on
      *"Expecting <Started application [...]> to have failed but context started successfully"*,
      the defaults case on **`Expecting value to be true but was false`** rather than on
      `30d`/null - `enabled` is read before `supersedeMaxAge` and AssertJ stops the case at its
      first failure, exactly as 003's T001 recorded of its own defaults case; an undefaulted
      `boolean` binds to `false` where an undefaulted `Duration` binds to `null`, so the prediction
      was right about the cause and wrong about which line reports it.
      **Deviation, recorded rather than argued after the fact**: the refusals live in
      `ConfigurationValidationTest` and the two binding cases in the new `OperationsPropertiesTest`,
      which is how both files named by the task are touched without either restating the other -
      003's T001 split the same way for the same reason.)
      (**Rewritten at gate round 3, and the rewrite is the point of this entry.** Gate rounds 1 and
      2 grew this task a family of cases that pinned a **cross-field start-up refusal**: the
      operations API enabled with `audit.http.enabled` or `authz.http.enabled` off, absent, or
      spelled `yes`, refused on a `yotresultsdistribution.servicebus.namespace` discriminator, with two
      "should start" counterparts for the local loop. The design owner withdrew the rule on
      2026-09-20 — the two switches are ordinary configuration an operator may set, and the pod
      always comes up — and constitution 5.0.0 redefined conditions (a) and (b) to the defaults
      instead. Every case that pinned the refusal is **deleted**, not weakened; three cases that
      pin its absence replace them; and two cases in `ShippedConfiguration` pin the defaults that
      now carry the conditions. The transport and value cases stay, re-gated on the transport's own
      switch, and one of them was **wrong**: the port rule read "one to five digits and positive",
      which accepts 65536. `an_audit_port_above_the_tcp_range_refuses_to_start` and
      `the_highest_port_there_is_should_start` are the boundary pair that fixes it.
      The base runner is back to the five identity and endpoint properties it carried before gate
      round 1 widened it with five library keys; the five pre-existing cases the widening was added
      for pass without it, because the rule that needed it is gone.
      red: 178 tests, 23 failed, every one an assertion — three "should start" cases on *"Expecting
      <Unstarted application context ...> to have not failed"*, the transport and range cases on a
      message naming `yotresultsdistribution.operations.enabled` where their own setting was expected, the
      two shipped-default cases on *`expected: "true" but was: null`*, and the five pre-existing
      cases the widened base runner had been propping up.)
      (**Extended at gate round 4**, with the pair of cases that pin what the defaults leave
      unsaid: `an_audit_filter_over_a_transport_that_is_off_should_say_so_at_start_up` and
      `a_pod_that_publishes_its_audit_events_should_say_nothing_about_them`. The shipped
      configuration puts the HTTP audit filter on over a transport that ships off, which is a pod
      serving the operations API unaudited — not refused, because the switches are configuration,
      and not visible either, because the filter that would have published is never constructed and
      the one that is swallows its own publishing failures. Captured with `support/CapturedLog`
      over `PropertiesValidator`'s own logger.
      red: 180 tests, 1 failed, an assertion — *"Expecting any element of: [] to satisfy the given
      assertions requirements but none did"*.)
- [x] **T005** [US1] `config/OperationsProperties` and `config/PropertiesValidator` — the record
      bound at `@ConfigurationProperties(prefix = "yotresultsdistribution.operations")` with its
      `@DefaultValue`s, following `GenerationProperties`' style, and T004's refusals written as the
      validator's existing helpers write generation's; plus the `authz.http.enabled` and
      `audit.http.enabled` defaults in `application.yaml` and the two files that override them.
      **`PropertiesValidator` is shared with 004** (which renames the generation grace period): add
      a method, do not reshape the class. Green: T004's cases.
      (green, first landing: `OperationsPropertiesTest` + `ConfigurationValidationTest`, 166 tests,
      0 failures; with `ReportPropertiesTest`, `TestProfileContextTest` and `AuditComponentScanTest`
      beside them, 177 passed. Checkstyle and PMD clean on main and test. `validateOperations` is
      private helpers under one package-private entry point, in the style the class's other rule
      families are written in; the static `validate(...)` is byte-for-byte what it was, which is
      what keeps 004's rename a clean rebase.)
      (**Rewritten at gate round 3 with T004.** What the validator holds now is four rules and no
      cross-field one. `validateTheAuditTransportNamesSomewhereToPublish` refuses a transport that
      is **switched on** and names no host, a blank host, or a port outside **1..65535**;
      `validateTheAuditFilterHasADocumentToRead` refuses an unset `audit.http.openapi-rest-spec`
      where **both** audit switches are on, which is where the parser that globs is actually built -
      every `audit.http.*` bean sits inside the `@AutoConfiguration` class `cp.audit.enabled` gates,
      so the HTTP half on over a transport that is off traps nothing; and the two duration rules are
      unchanged. `validateTheOperationsApiIsNeverServedUnauthorisedWhereItIsDeployed` and its audit
      twin are **deleted**, with `unaudited`, `unauthorised` and `servedOnADeployedPod`;
      `validateOperations` no longer takes `YotResultsDistributionProperties`, because the discriminator was
      the only thing that read them. The static `validate(...)` is **still** byte-for-byte what it
      was.
      The transport rules read `cp.audit.enabled` with an absent key taken as **off** rather than as
      the library's `matchIfMissing = true`: this service's `application.yaml` always sets the key,
      so an absent one means a context that did not load the file, and refusing one of those would
      be refusing a harness rather than a deployment.
      `application.yaml` gains `authz.http.enabled: ${AUTHZ_HTTP_ENABLED:true}` and
      `audit.http.enabled: ${HTTP_AUDIT_ENABLED:true}` — secure by default against two libraries
      whose own conditions default off — and `docker-compose.yml` and `application-test.yaml` set
      both `false` with the reason written beside them. The `authz.http.*` block proper is still
      T013's and the `audit.http.*` block proper still T043's; what lands here is the one key each
      that the default needs, and those tasks add the rest beside it.
      green: `ConfigurationValidationTest`, `OperationsPropertiesTest`, `AuditComponentScanTest`,
      `TestProfileContextTest` and `HttpSurfaceTest`, 193 tests, 0 failures; Checkstyle and PMD
      clean on main and test.)
      (**Extended at gate round 4.** `PropertiesValidator` gains `sayWhereNothingIsPublished`, the
      one thing the class says rather than refuses: one WARN naming `audit.http.enabled` and
      `cp.audit.enabled` where the first is on and the second is not. It is not a cross-field
      refusal and does not become one — the pod comes up exactly as before — and it exists because
      condition (b) of Principle III takes a key this repository cannot set: `cp.audit.enabled`
      ships `${CP_AUDIT_ENABLED:false}` so a laptop with no audit broker starts, and the deployed
      values file carries the rest of (b) (constitution 5.0.1, spec FR-045, deployment gate 5). The
      line names settings only — no caller input, no Key Vault value, no attached throwable.
      green: `ConfigurationValidationTest`, `TestProfileContextTest` and `AuditComponentScanTest`,
      187 tests, 0 failures; Checkstyle and PMD clean on main and test.)
- [x] **T006** [P] [US1] `config/PublicEventsFactoryTest` (new) — **the listener container is built
      on the public-event connection factory, not the audit one** (research R8: the audit starter's
      `auditConnectionFactory` and `auditJmsTemplate` are `@Primary`, and `PublicEventsConfig`
      currently injects `ConnectionFactory` by type). A context case that asserts the container
      factory's connection factory is the Boot-provided one. Red: it is the audit library's.
      (red: 2 tests, 1 failure, an assertion - *"Expected not same: ActiveMQConnectionFactory
      [serverLocator=... host=artemis-audit-invalid ...]"*. Exactly R8: the container the committed
      configuration builds is handed the **audit** broker's factory, because `@Primary` wins a
      by-type injection. The second case passes and is the premise: both factories are on the
      context and they are two objects.
      Written over the **real** auto-configurations - Boot's `ArtemisAutoConfiguration` and the
      library's own `ArtemisAuditAutoConfiguration` - rather than over hand-made stand-ins, because
      the `@Primary` that causes the fault lives in the library and a fake of it would be a test
      asserting against its own fixture.
      **A finding the task did not anticipate, and the one that decides T007's mechanism.** A first
      draft resolved Boot's factory by type, `getBean(ActiveMQConnectionFactory.class)`, and got
      the **audit** one - so the premise case failed too, reading as though Boot's Artemis
      auto-configuration had backed off under its `@ConditionalOnMissingBean(ConnectionFactory)`.
      It had not. Probing a real application context showed two beans:
      `jmsConnectionFactory -> CachingConnectionFactory (primary=false)` and
      `auditConnectionFactory -> ActiveMQConnectionFactory (primary=true)`. Boot's is behind a
      caching wrapper, so it is not of the type the audit one is, and **the only stable way to name
      it is its bean name**: both of Boot's Artemis configurations register under
      `jmsConnectionFactory`, while the type behind that name is
      `spring.jms.cache.enabled`'s choice. Asking by type is how a test - or a configuration class -
      ends up holding the audit one and saying nothing about it.)
- [x] **T007** [US1] `config/PublicEventsConfig` — take the connection factory **by name** rather
      than by type, so the injection says which one it means and cannot be won by somebody else's
      `@Primary`. This is the only change 005 makes to a production class outside `api/`,
      `application/`, `config/` settings and the deletions, and it is a correctness fix forced by
      the dependency. Green: T006.
      (green: `PublicEventsFactoryTest` 2 tests and `PublicEventsConfigTest` 3 tests, 0 failures;
      Checkstyle and PMD clean on main and test. `@Qualifier("jmsConnectionFactory")` on the
      factory method's parameter, with the bean name as a constant on the class - **by name and not
      by type**, per T006's finding: the audit library's factory is an `ActiveMQConnectionFactory`
      and so is Boot's whenever `spring.jms.cache.enabled` is false, so a qualifier written as a
      type would be the same trap in a new spelling. The reason is in the class javadoc, beside the
      unwrapping paragraph it belongs with, so that somebody deleting the qualifier reads what it
      was for. Nothing else in the class changed, and `PublicEventsConfigTest` - which calls the
      `@Bean` method directly - still passes unaltered.)

---

## Phase 2: Authorisation — the rules, the action name, the refusal body (6 tasks)

**Purpose**: Principle III condition (a), end to end, before a single endpoint exists. The rules
file and the action filter are what make "one explicit allow rule per action, and no default-allow"
true rather than claimed.

### Approved TDD exception — the ceremony, from Phase 2 onwards

**Design owner, 2026-09-20.** For increment 005, a task's tests land in the **same commit** as the
code they cover; the red run is **not recorded** and the commit order within a pair is **not
audited**. What the reviewers judge instead is the coverage gate (LINE 0.88 / BRANCH 0.85,
`config/**` excluded) and behaviour coverage **per endpoint** — allow and deny per group, every
refusal code the endpoint can answer, and the flag rule where the endpoint has one.

This supersedes, **for 005 only**, the 003-era condition that a third untested-first configuration
would be reverted. Every task still gets its test or tests; only the ceremony goes. It does not
relax any other gate: the operations-API gate, the no-swallowed-exception gate and the no-PII gate
are unchanged, and the "Approved TDD exceptions" section above still governs anything a later task
wants to claim beyond this.

- [x] **T008** [P] [US2] `api/OperationsRulesTest` (new) — **the drools rules, with no Spring**.
      Build a `KieContainer` from `acl/operations-rules.drl`, mock the
      `UserAndGroupProvider` global (note `inv.getRawArguments()[1]` for the varargs, per research
      R3), insert an `Outcome` and an `Action`, fire, read `outcome.isSuccess()`. Cases: each of the
      seven action names **allowed** for a caller in "Second Line Support"; each **denied** for a
      caller in some other group; an action name the file does not carry denied; and a text
      assertion that the file names no group but "Second Line Support". Seam: an empty
      `src/main/resources/acl/operations-rules.drl` carrying only the imports and the global.
      Red: every allow case on `expected: true but was: false` — default-deny with no rules.
      (Landed with T009 in one commit under the Phase 2 TDD exception above, so there is no red run
      to quote. 19 tests, 0 failures: seven allow cases and seven deny cases off one
      `@ParameterizedTest` pair, a caller in no group at all, one admitted group among others, two
      unknown-action cases - including the library's own `"POST /operations/batches/generate"`
      fallback shape, which is what an unmapped path would fall through to - and three assertions
      about the file's own text: seven rules and no more, seven group lists and every one of them
      exactly `"Second Line Support"`, and no `setSuccess(false)` anywhere. Checkstyle and PMD clean
      on test sources; two PMD findings were fixed rather than suppressed - the class loader taken
      from the current thread, and the nested class renamed off PMD's short-name list.)
- [x] **T009** [US2] `src/main/resources/acl/operations-rules.drl` — seven allow rules in the
      reference implementation's exact form (research R3): no `package` declaration, the two
      imports, the global, `$o: Outcome()` / `$a: Action(name == "yot-results-distribution-operations.<verb>")`
      / `eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "Second Line Support"))` /
      `$o.setSuccess(true)`. No deny rule anywhere — absence is the denial. Green: T008.
- [x] **T010** [P] [US2] `api/OperationsActionFilterTest` (new) — **the action name is derived by
      this service and overrides the caller**. `MockHttpServletRequest` plus an
      `ArgumentCaptor<HttpServletRequest>` on the chain, asserting `getHeader("CPP-ACTION")` on the
      wrapped request. Cases: all seven path+method combinations map to their action name
      (data-model §1–7); a caller-supplied `CPP-ACTION` naming a different action is **overridden**;
      the lookup is case-insensitive and `getHeaderNames()` includes it; an unrecognised path passes
      through with the header untouched; `POST` and `GET` on the same path map to different actions
      where they differ. Seams: `api/OperationsActionFilter` and `api/ActionRequestWrapper`
      skeletons. Red: the derived header is `null`.
      (Landed with T011 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 17 tests, 0 failures. Every case asserts the header the **chain** was handed, captured
      off the wrapped request with an `ArgumentCaptor<HttpServletRequest>`, because what the filter
      returns is nothing and what matters is what the next filter reads. Seven path+method cases off
      one `@CsvSource`; a forged `CPP-ACTION` overridden; the lookup case-insensitive through
      `getHeader` **and** `getHeaders`; the name present in `getHeaderNames`; and five cases that
      nothing is added where nothing is served - `/actuator/health` untouched with and without a
      caller's header, `GET` on the generate path, `GET` on the notify path, and a path below a
      served one.
      Deviation, additive: the task's "`POST` and `GET` on the same path map to different actions
      where they differ" names a pair these seven endpoints do not have - no path of this surface
      answers two methods. The two cases above assert the same property from the other side, that
      the lookup is keyed by method as well as path, which is what would have been proven.)
- [x] **T011** [US2] `api/OperationsActionFilter` and `api/ActionRequestWrapper` — a path+method to
      action map for the seven endpoints, at `Ordered.HIGHEST_PRECEDENCE` (research R11: ours,
      then authz at `+30`, then audit at `+50`), wrapping the request so the server's value wins.
      Registered in `config/OperationsWebConfig`. Green: T010.
      (green: `OperationsActionFilterTest` 17 tests, 0 failures; `TestProfileContextTest`,
      `AuditComponentScanTest` and `LogStatementSweepTest` beside it, all green, so the new
      `@Configuration` disturbs neither context. Checkstyle and PMD clean on main and test.
      The wrapper overrides `getHeader`, `getHeaders` **and** `getHeaderNames` together: a value
      visible through one accessor and not another is the same hole in a quieter spelling. The
      registration is a `FilterRegistrationBean` rather than a `@Component`, because the order is
      the whole reason the class exists and a registration is where an order is stated; it is
      gated on `yotresultsdistribution.operations.enabled`, default on, and mapped over every path rather
      than over `/operations/*` so that the list of this service's paths lives in exactly one
      place.)
- [x] **T012** [P] [US2] `api/OperationsErrorAttributesTest` (new) — **the `/error` body carries
      nothing the caller typed**. The authorisation filter refuses through `sendError`, which
      forwards to `/error` and never reaches a `@RestControllerAdvice` (research R5), and Spring's
      default body echoes the request path. Cases: a 401 body and a 403 body carry `status`, `title`
      and a bounded `reason` and **no** `path`, `trace`, `message` or `exception`; an unmapped path's
      body does not contain the path that was tried. Seam: `api/OperationsErrorAttributes`
      skeleton. Red: the body contains `path`.
      (Landed with T013 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 15 tests, 0 failures. The cases go through the bean directly rather than through a
      container, because `/error` is reached by a forward and the question is what the bean answers
      for a recorded status - and because that way every case can ask for **everything**
      (`MESSAGE`, `STACK_TRACE`, `BINDING_ERRORS`, `PATH`, `STATUS`, `ERROR`) and assert that none
      of it arrives. The request carries a distinctive typed path and a line of library words in
      `jakarta.servlet.error.message`, and the assertions are over the body's **values**, so a leak
      through any key at all fails rather than only a leak through the keys the task predicted.
      Deviation, additive: the 401/403 cases assert the body is **exactly** the three fields
      (`containsExactlyInAnyOrderEntriesOf`) as well as that it carries none of Boot's seven, which
      is the stronger form of the same rule.)
- [x] **T013** [US2] `api/OperationsErrorAttributes` and the `authz.http.*` settings block in
      `application.yaml` — the block exactly as research R1 records it, including
      `exclude-path-prefixes` re-listing `/actuator` **and** `/error` (setting it replaces the
      library's list wholesale, and dropping `/actuator` makes the probes answer 401),
      `reload-on-each-request: false` in deployed environments (the library default is `true`), and
      `deny-when-no-rules: true`. Green: T012.
      (green: `OperationsErrorAttributesTest` 15 tests, 0 failures; the whole `config` package and
      `TestProfileContextTest` beside it, green, so the new block binds and the replaced
      `ErrorAttributes` bean disturbs no context. Checkstyle and PMD clean on main and test.
      The block lands beside `authz.http.enabled`, which keeps its own comment and its
      `${AUTHZ_HTTP_ENABLED:true}`; no refusal, no discriminator and no second place to set it, per
      the warning on this task. Every key was checked against `HttpAuthzProperties`' own fields
      rather than against the README. The bean is registered in `config/OperationsWebConfig` beside
      the action filter rather than annotated `@Component`, so the reason it exists sits next to
      the filter whose refusals it renders. Six bounded codes and no more: `not-identified`,
      `not-permitted`, `no-such-path`, `method-not-allowed`, and the two family codes
      `request-refused` and `unexpected-failure`; the title is the framework's own reason phrase,
      which is a closed set, and a status it does not name is titled `Error`.)
      ⚠ `authz.http.enabled` itself **already exists** in `application.yaml`, at
      `${AUTHZ_HTTP_ENABLED:true}` (T005, gate round 3): the rest of the block lands **beside** that
      key rather than restating it, and the key keeps its comment. There is **no** start-up refusal
      behind it — the earlier FR-053 rule was withdrawn by the design owner on 2026-09-20 — so the
      default is the whole of how condition (a) is carried, and this task must not reintroduce a
      refusal, a discriminator or a second place to set the same switch. The library reads the
      value as the **literal** `true`, so every deployed values file writes `true` and not `yes`.

---

## Phase 3: The read endpoints — the flag and the two listings (6 tasks)

**Purpose**: the first three endpoints, the simplest, and with them the shape every later controller
copies: parse, call one application service, map. The batch listing is where the masking rule and
the "a controller may not hold a repository" rule both land.

- [x] **T014** [P] [US1] `api/FlagControllerTest` (new) — `@WebMvcTest` with `FeatureFlagReader`
      mocked and both filters off (`authz.http.enabled=false`, `cp.audit.enabled=false`).
      Cases: `Enabled` → `200 {"flag":"ON"}`; `Disabled` → `200 {"flag":"OFF"}`; `Unreadable` →
      **`200`** `{"flag":"UNREADABLE","reason":"<the reading's bounded code>"}` — **not** 503 (spec
      assumption 2); the reader is asked exactly once and its answer is not cached. Seams:
      `api/FlagController` and its response record. Red: 404, no mapping — **no**: the seam maps the
      path and throws `UnsupportedOperationException`, so the red is a failing assertion on the body.
      (Landed with T015 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 12 tests, 0 failures: ON, OFF and UNREADABLE all 200; every one of the six unreadable
      causes reaching `reason` as its own bounded code, off an `@EnumSource` so a seventh cause
      cannot be added without a case; the reader asked exactly once with
      `verifyNoMoreInteractions`; a second call asking again rather than answering from a cache;
      and a method the endpoint does not answer never reaching the reader.
      The slice sets a fourth property the task does not name, `yotresultsdistribution.generation.enabled=true`,
      and it is not incidental - see T015's entry.)
- [x] **T015** [US1] `api/FlagController` and `api/dto/FlagResponse` — the three readings through a
      switch expression over `FlagDecision`, as `CheckFlagCli.answered` did. Green: T014.
      (green: `FlagControllerTest` 12 tests, 0 failures; the whole `api` package and
      `TestProfileContextTest` beside it, green. Checkstyle and PMD clean on main and test; one PMD
      finding was fixed rather than suppressed, a loop over the unreadable causes becoming an
      `@EnumSource`.
      **A condition the task did not name, and a question for T040/T041.** `FeatureFlagReader` is
      contributed only where `yotresultsdistribution.generation.enabled` is true - `LiveFeatureFlagConfig`
      and `StubGenerationConfig` are both behind that key - so a `@RestController` holding one
      would fail the refresh on every pod that renders nothing, including the whole `test` profile.
      The controller therefore carries
      `@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled", havingValue = "true")`,
      which is exactly where `check-flag` answered `command-not-wired`. T041's text says the flag
      endpoint is among "the other four served normally" on a generation-off pod; on today's wiring
      it is a **fourth** endpoint that needs those beans, so T040/T041 must either add it to the
      not-wired set or make the reader unconditional. Not decided here: `LiveFeatureFlagConfig` and
      the `yotresultsdistribution.generation.*` block are outside this increment's range.)
- [x] **T016** [P] [US6] `application/BatchListingServiceTest` (new) — **`ListBatchesCli`'s reads,
      moved and unchanged**. Cases: a date's batches in the statement's order, each with its record
      count from `RegisterStore.batched` and its recipients from `RegisterNotificationRepository`;
      a batch with no court house yields `null` (the command printed `-`); the masking rule
      character for character — a local part longer than one keeps its first character, one of
      length one keeps none, an address with nothing before its `@` masks to `***@…`, an absent
      address masks to `***`; `recordedWhileOff` yields record id, hearing id, register date and
      flag state; a store that will not answer propagates rather than returning a partial listing.
      Seams: `application/BatchListingService` and the two result records. Red: the masking case.
      (Landed with T017 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 23 tests, 0 failures. The masking rule is a `@CsvSource` of seven rows - a local part
      longer than one, one of length two, one of length one, nothing before the `@`, no `@` at all,
      an empty address, and a null one in a case of its own - which is the rule character for
      character rather than the three cases the task names. The date's cases pin the statements'
      order in both directions (batches and recipients), the absent court house **and** the empty
      one, and an empty date answering an empty listing rather than silence. Four cases pin that a
      read which fails propagates: the date's own read, a record count, a recipient read, and the
      waiting listing - a listing that came back short is the answer support would act on.
      Two privacy cases carry the `PersonalDataMarkers` through: the records behind the counts hold
      a child's name, date of birth and ethnicity, and none of it is in what comes out, nor is the
      unmasked address.
      Deviation from the command, recorded: a batch with no court house yields `null` where
      `ListBatchesCli` printed `-`. That is the task's own wording (data-model §2) and it is the
      same fact in the shape JSON has for it; an empty string is treated as absent too, as the
      command did.)
- [x] **T017** [US6] `application/BatchListingService` — the body of `ListBatchesCli.listDate`,
      `print` and `listRecordedWhileOff` with the `Consumer<String>` printing removed and typed
      records returned. **Move, do not rewrite**: the diff is a move plus a return type. Green: T016.
      (green: `BatchListingServiceTest` 23 tests, 0 failures; `TestProfileContextTest` beside it,
      green. Checkstyle and PMD clean on main and test. `masked` and the record-count read are
      moved character for character, comment and all; what changed is that the two methods build
      `BatchListing` and `RecordedWhileOff` instead of calling a `Consumer<String>`, and that the
      `listed(...)` wrapper is gone - the store's own unchecked type leaves here and the caller
      decides what a caller should be told, which is what a service does in place of an exit code.
      The bean is declared in `config/OperationsWebConfig` and carries `@Profile("!test")`, because
      the store and both repositories carry it in `config/ProcessedLogConfig` - a file this
      increment may not touch - and a listing over readers that do not exist is a context that will
      not refresh.)
- [x] **T018** [P] [US1] [US6] `api/BatchesControllerTest` (new, listing cases only) and
      `api/RegistersControllerTest` (new, recorded-while-off cases only) — `@WebMvcTest` with
      `BatchListingService` mocked. Cases: the listing shape of data-model §2 and §3; a `date` that
      is absent → `400 missing-argument`; a `date` that will not read → `400 unreadable-argument`
      with `argument: date` and **without the value that was sent**; a store failure → `503`.
      Seams: the two controllers and their response records. Red: the body shape.
      (Landed with T019 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 14 tests, 0 failures - 9 on the batch listing and 5 on the recorded-while-off one.
      Besides the task's cases: a batch with no court house **omits** the key rather than rendering
      null; a date holding nothing answers `batches: []` and nothing waiting answers `records: []`,
      because silence is not an answer; an empty `date=` parameter is `missing-argument` like an
      absent one; and two cases assert the refusal bodies **as text**, that they carry neither the
      value that was sent nor anything the store said about itself.
      **One of those two found a real leak and is the reason it is written as text.** Spring fills
      a `ProblemDetail`'s `instance` with the request URI whenever it is left null, so every
      refusal came back carrying the path - which on `/operations/batches/{batchId}/notify` would
      be a value the caller typed. Fixed by setting the instance to an empty URI, which the
      problem-detail mixin's NON_EMPTY rule serialises away; setting it to null lets the framework
      fill it in again.)
- [x] **T019** [US1] [US6] `api/BatchesController#list`, `api/RegistersController#recordedWhileOff`
      and their dtos. Green: T018.
      (**What the first full build after Phase 3 found, and how it was closed.** 3744 tests, 12
      failed, in three families - all three of them "the repository still says there is no
      controller", which stopped being true the moment the first endpoint landed:
      • `config/GenerationMetricsContextTest` and `adapter/publicevents/DocumentEventListenerIT`
        would not refresh - `No qualifying bean of type FeatureFlagReader` for `flagController`.
        Both run on the **`test` profile with `yotresultsdistribution.generation.enabled=true`**, and both
        configurations that contribute a reader (`LiveFeatureFlagConfig`, `StubGenerationConfig`)
        are `@Profile("!test")`, so that combination has the switch without the bean. Closed by
        giving `FlagController` the profile condition beside its property one, which is the same
        sentence the reader's own configuration carries.
      • `config/HttpSurfaceTest.OnAGeneratingPod` and both `config/CliModeConfigTest` context cases
        asserted `containsExactly("basicErrorController")`. Re-pointed to the three operations
        controllers plus the fallback, **keeping the "exactly these and nothing else" force** - a
        controller arriving from a dependency, or a business endpoint arriving without the
        constitution amendment Principle III requires, still fails there. The list grows with the
        phases; **T056** still owes the re-point that stops naming beans at all, and **T054** still
        owes `CliModeConfigTest`'s deletion.)
      (green: both suites 14 tests, 0 failures; the whole `api` package, `TestProfileContextTest`
      and `LogStatementSweepTest` beside them, green. Checkstyle and PMD clean on main and test.
      Each controller parses, calls `BatchListingService`, and maps - no repository, no decision.
      The refusals are built in the controllers for now: `api/OperationsExceptionHandler` is
      T024/T025's, and the two private `refusal(...)` helpers are what it will absorb. A store that
      will not answer is caught, classified and answered `503 listing-failed`, with the cause
      logged by **class name** at ERROR exactly as `ListBatchesCli.listed` logged it - classified
      and answered, not swallowed.
      Both controllers carry `@Profile("!test")` for the reason `batchListingService` does.)

---

## Phase 4: The exception report, and the error mapping every endpoint uses (6 tasks)

**Purpose**: the last read endpoint, and with it the `@RestControllerAdvice` that turns every
refusal in the increment into a `ProblemDetail`. It lands here rather than in Phase 3 because the
exception report has the widest refusal set of any endpoint.

- [x] **T020** [P] [US6] `application/OnDemandExceptionReportServiceTest` (new) — **`ReportExceptionsCli`'s
      window and sinks, moved and unchanged**. Cases: `since` as an ISO instant, as an ISO-8601
      duration and as `<n>d`/`<n>h`/`<n>m`/`<n>s`; a zero or negative window refused; no `since` →
      the window from the previous scheduled run, computed from the report cron and zone exactly as
      the command computed it; e-mail asked with the output switched off → the
      `email-output-disabled` refusal; e-mail asked with the output on and no sink → the
      `email-output-not-wired` refusal; the sink selection (log always, e-mail only when asked); and
      "not every sink took it" distinguished from "the report could not be built". **The flag reader
      is a strict mock and is never touched** — the command read it nowhere and neither does this.
      Seams: `application/OnDemandExceptionReportService` and its result record. Red: the window
      forms.
- [x] **T021** [US6] `application/OnDemandExceptionReportService` — the body of
      `ReportExceptionsCli.asked` and `reported` minus the parsing and the printing, returning the
      report, the per-sink outcomes, the run id and the duration. `RunCorrelation.under(...)` is
      kept: an on-demand report is a run and carries a run id, as it did. Green: T020.
      (Green: `OnDemandExceptionReportServiceTest` 21 tests, 0 failures. The service takes the cron,
      the zone and the e-mail switch as values rather than `ReportProperties`, exactly as
      `ExceptionReportService`'s three limits are handed in, so the application layer does not
      depend on the shape of a configuration file. The two seams the refusals travel on land with
      it: `domain/OperationsReason`, the closed set of bounded codes from data-model "The reason
      codes", and `domain/OperationsRefusedException`, which carries a reason, this service's own
      name for an offending argument and bounded extras - never a value the caller supplied. The
      command's window reading is duplicated rather than moved, because `ReportExceptionsCli` is
      deleted whole in Phase 10 and delegating it first would churn a suite that is about to go.)
- [x] **T022** [P] [US6] `api/ExceptionReportsControllerTest` (new) — `@WebMvcTest`. Cases: the
      `200` shape of data-model §7, entries and counts and truncation and per-sink delivery and the
      run id and the duration; an empty window answers `entries: []` and the counts, never silence;
      `409 email-output-disabled`; `409 email-output-not-wired`; `400 unreadable-argument` with
      `argument: since` and no echo of the value; `500 report-not-built`; `500
      report-not-delivered` carrying which sink refused. Seams: the controller and its dtos.
      Red: the body shape.
      (Landed with T023 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 9 tests, 0 failures: the §7 shape field by field including the three identifiers a
      failed request does not have asserted **absent** rather than null; an empty window answering
      `entries: []` and zero-filled counts; the two arguments passed through; no body at all read
      as the default window; both e-mail refusals; the unreadable window asserted against the whole
      response text, not just the fields, so an echo anywhere fails it; `report-not-built`; and
      `report-not-delivered` carrying `delivered` and the run id.)
- [x] **T023** [US6] `api/ExceptionReportsController` and its dtos. Every entry field is an
      identifier, a bounded code or a count, and absent fields are **omitted** rather than rendered
      — the same rule `ReportExceptionsCli.carried` applied. Green: T022.
- [x] **T024** [P] [US4] `api/OperationsExceptionHandlerTest` (new) — **the status map and the
      no-echo rule, once, for everything**. Cases, one per row of data-model "Common": a body that
      will not parse → `400`; a body with an unknown field → `400` (the request contract is closed,
      FR-028); every bounded reason reaches `reason` and none reaches `detail`; **no response body
      contains a value from the request** — driven by sending distinctive values in every field and
      asserting none appears; an exception message never reaches the body; an unmapped method on a
      mapped path answers without a stack trace. Seam: `api/OperationsExceptionHandler`. Red: the
      unknown-field case is accepted.
- [x] **T025** [US4] `api/OperationsExceptionHandler` (`@RestControllerAdvice`,
      `@Order(HIGHEST_PRECEDENCE)`) and the `ObjectMapper` setting that makes an unknown field a
      failure on the request records only. The full map of data-model FR-023: `400`, `404`, `409`,
      `500`, `501`, `502`, `503`, `504`. **`@ControllerAdvice` is permitted for this package and
      nowhere else** (`technical-rules.md`); it must be unreachable from the listeners and the jobs.
      Green: T024.
      (Green: `OperationsExceptionHandlerTest` 39 tests, 0 failures; the whole `api` package 190
      tests, 0 failures. The advice is declared `basePackages = "…yotresultsdistribution.api"`, so it is
      unreachable from the listeners and the jobs by declaration and not only by fact. It has **no
      `Exception` fallback**: an unmapped path, an unmapped method and a genuine defect reach
      Boot's `/error` and come back through `OperationsErrorAttributes` in the same bounded shape,
      and a fallback here would have to guess a bounded code for something nobody classified.
      The closed request contract is `api/OperationsRequestBodies`, a `WebMvcConfigurer` that
      registers a `FAIL_ON_UNKNOWN_PROPERTIES` mapper **per request-record type** on the HTTP
      converter - not globally, because the auto-configured mapper is the one every adapter reads
      hearing payloads and platform envelopes with, and making those fail on an unknown field
      would dead-letter a night's hearings for somebody else's new field. It is a
      `WebMvcConfigurer` rather than a bean on a configuration class so the `@WebMvcTest` slices
      pick it up. `BatchesController` and `RegistersController` now raise
      `OperationsRefusedException` instead of building their own `ProblemDetail`s, and
      `ExceptionReportsController`'s transitional `catch` is gone: the advice is the only producer
      of a refusal body.)

---

## Phase 5: Supersede — the endpoint that is stricter than its command (4 tasks)

**Purpose**: the second cutover lever that this endpoint would otherwise be. Every protection here
was confirmed by the design owner on 2026-09-19 and is a requirement, not a precaution.

- [x] **T026** [P] [US6] `application/OperationsSupersessionServiceTest` (new) — the guard, with the
      store and the flag reader mocked. Cases: the flag **OFF** → the store is asked and the count
      returned; the flag **ON** → `FLAG_ON`, and **the store is never touched**; the flag
      **unreadable** → `flag-unreadable`, store never touched (fail-closed); `dryRun` → the count is
      read and **nothing is superseded**; an instant in the future → refused; an instant older than
      `supersede-max-age` → refused; the flag is read **uncached**, once per call; a store failure →
      the `supersession-failed` classification. Seam: `application/OperationsSupersessionService`.
      Red: the flag-ON case supersedes.
      (Landed with T027 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. 12 tests, 0 failures: the flag OFF superseding and answering the count, with the read
      asserted to happen exactly once; the dry run counting three of four rows and never reaching
      the write; a store outage as `supersession-failed` with the store's own words absent; the
      flag ON refusing `FLAG_ON` with `verifyNoInteractions(registers)`; every
      `UnreadableReason` failing closed off an `@EnumSource`; a dry run refused by the same lever;
      and the four bound cases - absent, future, older than the bound, and exactly at it -
      the first three asserting the flag was not read either.)
- [x] **T027** [US6] `application/OperationsSupersessionService` — the flag read through the same
      uncached `FeatureFlagReader` path, the two bounds against `Clock` and `OperationsProperties`,
      the dry run, and `RegisterStore.supersedeSharedBefore` otherwise. There is **no** override
      parameter and none may be added. Green: T026.
      (Green: `OperationsSupersessionServiceTest` 12 tests, 0 failures. Two decisions are worth
      writing down. **The bounds are checked before the flag is read**: a malformed request is
      refused on its own terms, and consulting the cutover state to reject one would make a `400`
      depend on something the caller cannot see - which is also the order data-model §6 lists the
      refusals in. **The dry run is composed from two existing reads**,
      `recordedUnbatchedBefore` plus `recordedWhileOff` filtered on the bound, whose predicates
      together are exactly the write's - `ACTIVE_UNBATCHED_PREDICATE` tests
      `recorded_flag_state = 'ON'` and `RECORDED_WHILE_OFF` tests `<> 'ON'`, so the union is the
      write's three predicates with no flag-state test. A dedicated count read would have been
      one statement instead of two, but `application/RegisterStore` and `persistence/*` belong to
      the 004 tree under this increment's coordination contract; the seam is noted for after the
      merge.)
- [x] **T028** [P] [US6] `api/RegistersControllerTest` (extend) — the supersede slice cases of
      data-model §6: `200` with the count, the instant and `dryRun`; `400 missing-argument` for an
      absent instant — **never defaulted**; `400 unreadable-argument` with `argument: sharedBefore`;
      `400 SUPERSEDE_INSTANT_IN_FUTURE`; `400 SUPERSEDE_INSTANT_TOO_OLD`; `409 FLAG_ON`;
      `409 flag-unreadable`; `503 supersession-failed`. Red: the mapping.
      (Landed with T029 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. `RegistersControllerTest` 20 tests, 0 failures, of which 10 are the rollback: the
      `200` with the count, the bound and `dryRun: false`; the dry run; the absent instant; the
      unreadable instant asserted against the whole response text and with the service never
      reached; both `400` bound codes; `409 FLAG_ON`; `409 flag-unreadable`;
      `503 supersession-failed` with no count in the body; and `ignoreFlag: true` refused as a
      field this request does not take, which is the closed contract making "there is no override
      here and there never will be" a property of the wire rather than of a document.)
- [x] **T029** [US6] `api/RegistersController#supersede` and its request/response records. Green:
      T028.
      (Green: `RegistersControllerTest` 20 tests, 0 failures. `sharedBefore` is carried as a
      **string** on the request record and parsed in the controller, so a value that will not read
      is this service's own `400 unreadable-argument` naming the argument rather than a binder
      message quoting the caller's characters back; the response carries the parsed instant, not
      those characters. `SupersedeRequest` joins `ExceptionReportRequest` in
      `OperationsRequestBodies`'s closed set. The service is contributed in
      `config/OperationsWebConfig` beside the other two, taking the flag reader that T040/T041's
      decision made unconditional.)

---

## Phase 6: Regeneration — the asynchronous one (8 tasks)

**Purpose**: the endpoint that changes production data. It is the largest departure from its
command's shape (`202`, a background run, the nightly lock **taken**) and every part of that
departure is forced — research R16 has the reasoning.

- [x] **T030** [P] [US3] `application/RegisterRegenerationServiceTest` (new) — **`GenerateRegisterCli`'s
      orchestration, moved and unchanged**, its existing cases re-pointed from the CLI class.
      Cases: narrowing by court house and by batch id; a FAILED batch released; a batch withheld
      `key-in-flight` when its key has one in flight; a batch withheld `outside-the-bound` when the
      operator's narrowing excludes one of its registers; `activeUnbatched` folded in only when no
      batch id was given; the de-duplication by `outputId`; the run deadline; the tally — released,
      registers, batches, requested, deferred — matching the command's counts exactly for the same
      inputs. Seams: `application/RegisterRegenerationService` and its `Selection` and
      `RegenerationTally` records. Red: the withheld-reason case.
- [x] **T031** [US3] `application/RegisterRegenerationService` — the body of
      `GenerateRegisterCli.generate`, `narrowed`, `released`, `withheldReason`, `registers` and
      `request`, with the printing replaced by the tally record. `Selection` moves with it. **Does
      not touch `RegisterGenerationJob` or `GenerationReconciler`** (004's). Green: T030.
      (Landed with T030 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `RegisterRegenerationServiceTest` 18 tests, 0 failures; Checkstyle and PMD
      clean on main and test. The five methods moved character for character with two changes and
      no third: the printing became `RegenerationTally`, and the two withheld reasons became
      `OperationsReason.KEY_IN_FLIGHT` / `OUTSIDE_THE_BOUND` rather than the command's own string
      constants - the same characters on the wire, now from the one closed set the status map is
      built on. `Selection` and `RegenerationTally` are public nested records of the service rather
      than two more files, which keeps the increment's file set exactly what the coordination
      contract names.
      **The flag is read nowhere in here, and that is the one departure from the task's wording.**
      T031's own list of moved methods is `generate`, `narrowed`, `released`, `withheldReason`,
      `registers` and `request` - `gated` is not among them - and T033 says the launcher reads the
      flag. It has to be the launcher: `FLAG_OFF` is a `409` the caller sees (data-model §4), so
      the read happens before the `202`, and a second read in here would be a second reader of the
      one lever with different semantics. The service is told `overridden` and carries it on the
      tally.
      A run that stops leaves through `OperationsRefusedException(GENERATION_FAILED)` carrying the
      counts it had reached, which is "the day stands as whatever this run had already written
      down" as a bounded extras map rather than as a printed line.)
- [x] **T032** [P] [US3] `application/OperationsRunLauncherTest` (new) — **the lock is taken, not
      asked about** (research R12). With a `LockProvider` mock: the run id is minted and recorded
      **before** the work is submitted (ids before calls); the launcher attempts the lock with the
      register-generation lock name and a non-blocking configuration; an empty `Optional` → the work
      does not run and `SCHEDULE_RUNNING` is the run's recorded outcome; a lock obtained → the work
      runs and the lock is released in a `finally`, including when the work throws; the work is
      submitted to the generation scheduler's executor and not run on the calling thread. Seam:
      `application/OperationsRunLauncher`. Red: the work runs without a lock.
- [x] **T033** [US3] `application/OperationsRunLauncher` — the `202` hand-off: validate, read the
      flag through `FeatureFlagGate`, mint and record the run id, submit to the generation
      executor, and inside the submitted task take `LockProvider.lock(...)` for
      `RegisterGenerationJob.LOCK_NAME`, run `RegisterRegenerationService`, release. Green: T032.
      (Landed with T032 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `OperationsRunLauncherTest` 12 tests, 0 failures; `HttpSurfaceTest` 11 tests,
      0 failures beside it, which is where the new wiring is proven over the real scan. Checkstyle
      and PMD clean on main and test; one PMD finding was fixed rather than suppressed, the fourth
      `"!test"` in `OperationsWebConfig` becoming a named constant.
      Four things the task's sentence does not spell out and the code had to decide:
      **the override's cross-field rule is validated here**, first, before the flag is read - an
      `ignoreFlag` without a `batchId` is `400 OVERRIDE_REQUIRES_BATCH` and reads nothing, because
      a break-glass over a whole register date is not the break-glass `--ignore-flag` was;
      **the bounded wait is measured on elapsed time, not on the injected clock** - the clock a run
      is correlated by may be a fixed one, and a budget nothing advances is a budget that never
      runs out - with `LockSupport.parkNanos` rather than a sleep, so the zero default makes
      exactly one non-blocking attempt and never parks;
      **the background task is a settlement boundary**: a throwable leaving it would go into a
      `Future` nobody reads, so every ending is classified and written down under a bounded code -
      the refusal's own, or `generation-failed` with the defect named by class - and the lock is
      given back in a `finally` either way;
      **the run id is the launcher's own field, not `RunCorrelation`'s** - that class mints its own
      and has no "under this id" form, and adding one would be an edit to `batch/` this increment
      does not need. Every line the launcher writes carries `run_id=` explicitly, which is what the
      caller was answered with.
      The two beans are contributed by a nested `OperationsWebConfig.GenerationBackedOperations`,
      conditional on `yotresultsdistribution.generation.enabled` and on not-CLI-mode: the executor is the
      generation scheduler taken by `SchedulingConfig.GENERATION_SCHEDULER` and adapted to a plain
      `Executor`, so no Spring scheduling type reaches the application layer.
      ⚠ **The operator trigger is not on the run report line yet.** T036/T037 add the field and run
      after the 004 rebase; a `TODO` in `OperationsRunLauncher.run` names T037, and until then the
      trigger and the override are on the launcher's own line.
      Gate round 1: **the refusal line now carries the partial tally** the regeneration attaches to
      `GENERATION_FAILED` (`released`, `registers`, `batches`, `requested`, `deferred`). The `202`
      is answered before any work, so no status can ever carry it and that line is the only place
      "the day stands as whatever this run had already written down" can be read. The refusal's own
      `date` is skipped because the line already says it. Red:
      `a_run_that_stopped_part_way_should_say_what_it_had_already_written_down`. Green:
      `OperationsRunLauncherTest` 14 tests, 0 failures; Checkstyle and PMD clean.)
- [x] **T034** [P] [US3] `api/BatchesControllerTest` (extend) — the generate slice cases of
      data-model §4: `202` with the run id and `overridden`; `400 missing-argument` for an absent
      date; `400 unreadable-argument` for each of `date`, `batchId`, `recordedBefore`, naming the
      argument and never the value; **`400 OVERRIDE_REQUIRES_BATCH` for `ignoreFlag: true` with no
      `batchId`**, and the accepted override *with* one (design owner, 2026-09-19);
      `409 flag-off`; `409 flag-unreadable`; a date-wide regeneration with the flag ON accepted.
      Red: the override cases.
- [x] **T035** [US3] `api/BatchesController#generate` and its request/response records. Green: T034.
      (Landed with T034 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `BatchesControllerTest` 27 tests, 0 failures; the whole `api` package and
      `HttpSurfaceTest` beside it, green. Checkstyle and PMD clean on main and test.
      The controller parses the three values that have to be read - one at a time, each under its
      own name - and decides nothing else: the cross-field rule on the override, the flag and the
      lock are all the launcher's, and the `202` body carries this service's own parse of the date
      rather than the characters the caller typed. `GenerateRegisterRequest` joins
      `OperationsRequestBodies`'s closed set, so a field this service does not take is `400`.
      **The class gained a second `@ConditionalOnProperty`, on `yotresultsdistribution.generation.enabled`,
      in this commit rather than in T041's.** It had to: the launcher is contributed only where the
      generating half is, and a controller left scanned over a bean that does not exist is an
      `UnsatisfiedDependencyException` at refresh - so the commit that adds the endpoint is the
      commit that has to gate the class, or the build is not green. `@ConditionalOnProperty` is
      `@Repeatable` on Boot 4.1, so the two conditions are two annotations and not a new
      meta-annotation. `HttpSurfaceTest.OnAPodThatRendersNothing` now expects the three controllers
      that are served everywhere; T041 adds the fallback to that list.)
- [x] **T036** [P] [US3] `domain/RunReportTest` (**new here, extended after the rebase if 004
      landed it first** — there is no such suite in either tree today, the line is asserted only
      inside `batch/RegisterGenerationJobTest`, which is 004's and must not be touched, and 004's
      own T017 names this file "(extend)". Creation is **004's** by the plan's coordination
      ledger, because its cases are about the format string 004 rewrites; this task adds the
      trigger cases beside them and keeps every released-batch assertion whole) — **the operator
      run says it was one**. The run line carries `trigger=operator` for a
      regeneration launched over HTTP and the scheduler's own value otherwise, and
      `reason=overridden` where the flag was overridden — the same field `FeatureFlagGate` already
      counts and logs, which is **kept**, not replaced.
      ⚠ **`domain/RunReport` is also touched by 004** (its run line loses `reconciled=` and gains
      `released_batches=`/`released_registers=`). Add a field; do not reshape the line. Expect a
      textual conflict on the rebase and resolve it by keeping both. Red: the trigger is absent.
- [x] **T037** [US3] `domain/RunReport` — the trigger field, added so that **the existing call site
      in `batch/RegisterGenerationJob` does not change**: the scheduler's value is the default and
      the operator's is a second factory. The job is 004's and 005 does not edit it; if the field
      cannot be added without editing it, the task stops and goes back to the orchestrator rather
      than reaching into the other tree's file. Green: T036.
      (Landed with T036 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `RunReportTest` 8 tests, `OperationsRunLauncherTest` 16,
      `RegisterRegenerationServiceTest` 18 and `RegisterGenerationJobTest` 74, 0 failures;
      Checkstyle and PMD clean on main and test after one finding was fixed rather than suppressed
      (`Trigger`'s field renamed `spelling`, which is how `OperationsReason` already spells it).
      **The record's construction call sites in the job did not change**, which is what the task
      asked for: `trigger` is a twelfth component with the eleven-component constructor kept beside
      it as the schedule's, and `RunReport.byOperator` is the second factory. A `null` trigger is
      the schedule's too - a run is the schedule's until somebody says it was theirs.
      **004 had merged by the time this ran, so the job was editable and two things in it did
      change**, neither of them a construction: the line gained `trigger={}` after `run_id=`, and
      `recorded` became `public static recorded(report, runId)` so that the launcher writes the
      **same** line rather than a second spelling of a night. The correlation is handed in because
      a launched run's id is the one its caller was answered with and not the ambient
      `RunCorrelation`'s.
      **The launcher's interim line is gone and the TODO with it.** What replaces it is the run
      report line under `trigger=operator`, plus one short line beside it carrying the two things
      the report's fields have no room for - the day and how many FAILED batches the run would not
      release. `RegisterRegenerationService` therefore had to earn the counts a report needs and
      the tally did not carry: the row partition per batch status (from `assembled.records()`,
      counted exactly where the schedule counts it), how many batches the release actually gave
      back, and how many registers reached no batch. `RegenerationTally` gains one component,
      `report`. `contended` is nought and the snapshot is `unread`, because a regeneration runs no
      stale-batch pass and takes no settled reading - four zeroes claiming a settled nothing would
      be a measurement nobody took.)

---

## Phase 7: Notify, and the pod without a generation half (5 tasks)

**Purpose**: the last endpoint, and the one honest answer for a deployment that cannot serve three
of them.

- [x] **T038** [P] [US6] `api/BatchesControllerTest` (extend) — the notify cases of data-model §5,
      with `RegisterNotifierService` mocked. `SETTLED` → `200` with the tally;
      **`ALREADY_NOTIFYING` → `409`** (another notifier holds the claim; this call changed nothing);
      **`CLAIM_LOST` and `INCOMPLETE` → `500`** carrying the disposition (the call tried and got
      part-way); a `batchId` that is not a UUID → `400` naming `batchId`; a batch that does not
      exist → `404 UNKNOWN_BATCH`, **distinguished from** a store outage → `503 STORE_UNAVAILABLE`;
      notificationnotify refusing → `502`, not answering → `504`. Red: the three-way disposition
      split.
- [x] **T039** [US6] `api/BatchesController#notify` and the disposition mapping. The service must
      distinguish "no such batch" from "the store would not answer" — the CLI caught both as one
      `RuntimeException`; if it does not already, that distinction is made **in the application
      service**, not in the controller. Green: T038.
      (Landed with T038 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `BatchesControllerTest` 39 tests, 0 failures; the whole `api` package and
      `HttpSurfaceTest` beside it, green. Checkstyle and PMD clean on main and test.
      **The distinction is made in the controller, not in the application service, and the reason
      is the coordination contract.** `application/RegisterNotifierService` is a file this tree may
      call and must not edit. An outage arrives as `StoreUnavailableException` or as one of the
      three Spring shapes a driver that never reached the database wears, and the controller
      catches exactly those - the same four the listing catches, for the same stated reason - and
      maps them to `503 STORE_UNAVAILABLE`.
      **`404 UNKNOWN_BATCH` is not answered, and gate round 1 is why.** The notifier raises a bare
      `IllegalStateException` for three different endings: `noSuchBatch` (the claim answered
      `ABSENT`), `documentOf` (a batch that exists and carries no document, reachable for any
      `FAILED` or `PENDING` batch with recipients, since the claim guards no status) and
      `rowThatWon` (a notification row the store refused and then holds none for). Only the first
      is a `404`, the type does not say which is which, and a `404` over either of the others sends
      an operator to check an identifier that is right - so all three keep the command's own
      `500 resend-failed`, pinned by
      `a_batch_that_carries_no_document_should_not_be_answered_as_no_such_batch`. The `404` of
      data-model §5 returns when `RegisterNotifierService.noSuchBatch` raises a typed exception the
      controller can catch alone: a one-line change in that file, left for whoever owns it next,
      recorded here rather than made quietly. `UNKNOWN_BATCH` keeps its row in the status map
      meanwhile, so the map stays closed and the code is there to be raised.
      `NotificationFailedException` is mapped too, `502` on a refusal and `504` on a silence, so a
      consumed platform contract's two different answers stay two. It cannot escape `resendFailed`
      today - the notifier catches it per row - and the mapping is what keeps that true by
      accident rather than by luck if it ever does.
      `ALREADY_NOTIFYING` is a `409` here where the command exited SUCCESS: over HTTP the call
      changed nothing and the status says so, which is data-model §5.
      **The `404` is answered as of the 004 merge, and the one-line change this entry left for
      "whoever owns it next" is made.** The 005 tree owns `application/RegisterNotifierService`
      once 004 has merged, so `noSuchBatch` now raises `domain/NoSuchBatchException` - an
      `IllegalStateException` subtype, so nothing that caught the three together stops catching it
      - and the controller catches that one alone, ahead of the bare type, for
      `404 UNKNOWN_BATCH`. `documentOf` and `rowThatWon` keep `500 resend-failed`, and
      `a_batch_that_carries_no_document_should_not_be_answered_as_no_such_batch` still pins that.
      Green: `BatchesControllerTest` 41 tests and `RegisterNotifierServiceTest` 52, 0 failures;
      Checkstyle and PMD clean on main and test. The distinction T039 asked for between an outage
      and a batch that does not exist stays in the controller, because the two shapes an outage
      arrives in are Spring's and belong nowhere nearer the store.)
- [x] **T040** [P] [US6] `api/NotWiredControllerTest` (new) — `501 COMMAND_NOT_WIRED` on a pod with
      `yotresultsdistribution.generation.enabled=false` for the three endpoints that need those beans
      (generate, notify, the batch listing), and the other four served normally. This is exactly
      what the CLI answered on such a pod; a `404` would read as a mistyped URL and a `500` as a
      bean-definition error reaching an operator. Red: the context fails to start for want of a
      bean, or the path answers 404.
- [x] **T041** [US6] `api/NotWiredController` and `config/OperationsWebConfig` — the controllers
      that need the generation beans registered `@ConditionalOnProperty` on
      `yotresultsdistribution.generation.enabled`, the not-wired fallback registered when it is off, and the
      whole set conditional on `yotresultsdistribution.operations.enabled`. **Reads the generation property;
      does not change it** (004 owns that block). Green: T040.
      (Landed with T040 in one commit under the Phase 2 TDD exception, so there is no red run to
      quote. Green: `NotWiredControllerTest` 4 tests and `HttpSurfaceTest` 14 tests, 0 failures;
      the whole `api` package green beside them. Checkstyle and PMD clean on main and test. The
      generation property is read and not changed: `yotresultsdistribution.generation.*` is untouched.
      **The gating half of this landed in T035's commit**, because it had to - the commit that
      added an endpoint over a generation-only bean is the commit that had to gate the class, or
      the build was not green. What lands here is the fallback: `api/NotWiredController`, mapped
      over all three batch paths and registered by the inverse condition
      (`havingValue = "false", matchIfMissing = true`) beside the operations switch, so exactly one
      of the two controllers is contributed on any pod. It raises the same
      `OperationsRefusedException` every other refusal on this surface raises, so the `501` body is
      built by the one status map and carries no path, no identifier and nothing the caller typed.
      **The batch listing is in the not-wired set, and the flag endpoint is not.** That is T040's
      own wording and the gate-round-1 note's; the reason it is true of the listing is not that its
      readers are missing - `BatchListingService` is contributed on every pod - but that the
      listing shares a class with the two endpoints that are, and serving two of the three paths
      while mapping nothing for the others would be worse than saying so. The flag endpoint's own
      conflict was settled separately, above.
      **The full build found a third condition the controller needs, and it landed straight after.**
      `yotresultsdistribution.cli=true` withdraws `SchedulingConfig` and `SchedulingInfrastructureConfig`,
      so a command JVM holds neither the generation scheduler nor the ShedLock provider the
      regeneration hand-off is built over - and with the generation switch on, that made
      `batchesController` an `UnsatisfiedDependencyException` at refresh and cost every command its
      context (12 suites failed on it: `CliModeConfigTest.ACliContext` and `CliDispatchIT`). The
      controller therefore carries `@Conditional(CliModeConfig.NotCliMode.class)` as well, and
      `CliModeConfigTest` now states the consequence rather than the old "both contexts hold the
      same set": a JVM about to exit maps three fewer paths, and the whole question goes away in
      Phase 10 with the CLI.
      **One part of the task is deliberately not done**: folding the `@Profile("!test")` on the two
      listing controllers into the same gating. It is a tidy-up with no behaviour attached, the
      `test` profile genuinely has no database, and doing it would put a refactor of two working
      controllers into a commit about a fallback. Recorded here rather than done quietly.)

      **Half of this landed in gate round 1's remediation**, because it was not a shape improvement
      but a crash: `yotresultsdistribution.operations.enabled=false` withdrew the only `BatchListingService`
      bean while the controllers went on being component-scanned, so the switch could not be turned
      off without an `UnsatisfiedDependencyException` at refresh, and `FlagController` went on
      serving `/operations/flag` whatever it said. The three controllers, the action filter and the
      listing bean now all carry
      `@ConditionalOnProperty(yotresultsdistribution.operations.enabled, matchIfMissing = true)`;
      `OperationsErrorAttributes` deliberately does not, because a pod with the surface off still
      answers whatever an operator tried and Boot's own body for that echoes the path they typed.
      `HttpSurfaceTest.WithTheOperationsApiSwitchedOff` is the context case over the real scan.
      What is left for T041 is the generation-property half: the not-wired fallback, and folding
      the ad-hoc `@Profile("!test")` on the two listing controllers into the same gating.

      **The flag endpoint's conflict is decided, ahead of the task (design call, 2026-09-21).**
      T040 names "the three endpoints that need those beans (generate, notify, the batch listing),
      and the other four served normally" — but `FlagController` was carrying
      `@ConditionalOnProperty(yotresultsdistribution.generation.enabled)` too, because that was the only
      place a `FeatureFlagReader` was contributed. Those two cannot both be true. The decision is
      T040's wording: **`GET /operations/flag` is served on every pod**, including one with the
      generation half off. Which implementation is live is not a property of the replica an
      operator happened to reach, and answering `501 command-not-wired` there would make the
      lever's state look like one.
      What moved for it is the reader, not the endpoint: `config/LiveFeatureFlagConfig` no longer
      carries the class-level `yotresultsdistribution.generation.enabled` condition (its `!test` profile
      gating and its LIVE/STUB mode selection are untouched, and `StubGenerationConfig`'s bean
      already had no such condition), and the workload identity is built only where an endpoint
      names a store — a pod with no nightly job is deployed with no App Configuration endpoint,
      `PropertiesValidator` asks for one only once generation is on, and refusing to start for want
      of a credential to read a store nobody configured would have cost every non-generating pod
      its start-up. Such a pod answers `UNREADABLE unreadable-not-configured`, a reading with a
      cause on it rather than a refusal. Pinned by `HttpSurfaceTest.OnAPodThatRendersNothing`.
      This is the one `config/` change outside `api/` the increment's coordination contract
      permits, and it is spent here.
      T041 therefore keeps its not-wired fallback for **generate, notify and the batch listing**
      only.

---

## Phase 8: The contract, the audit facts, and the real filter (7 tasks)

**Purpose**: the three conditions that cannot be proven endpoint by endpoint — that the OpenAPI
document and the controllers agree, that the audit event carries what it must and no body, and that
the **real** authorisation filter refuses the people it should.

- [x] **T042** [P] [US1] `api/OpenApiContractTest` (new) — **both directions**. Parse
      `src/main/resources/openapi.yaml`; assert every mapped path and method in
      `RequestMappingHandlerMapping` (excluding actuator) is described, and every path described is
      mapped; assert `/operations/batches/{batchId}/notify` declares `batchId` as an `in: path`
      parameter — the audit filter registers a path **only** if it does (research R7); assert every
      bounded `reason` the handler can emit appears in the document's enumerations. Seam: an
      `openapi.yaml` with the info block and no paths. Red: seven paths mapped, none described.
- [x] **T043** [US1] `src/main/resources/yot-results-distribution-openapi.yaml` — the seven endpoints as data-model describes
      them, plus the rest of the `audit.http.*` settings block **beside** the
      `enabled: ${HTTP_AUDIT_ENABLED:true}` key T005 already landed (do not restate it, and do not
      flip it: the `true` default is how condition (b) of Principle III is carried, and there is no
      start-up refusal behind it):
      `openapi-rest-spec` and `include-payload-body: false` **explicitly** (the library default is
      `true` and would publish every response body), **and the `yotresultsdistribution.operations` block**:
      `enabled: ${YOTRESULTSDISTRIBUTION_OPERATIONS_ENABLED:true}`, `supersede-max-age: 30d`,
      `lock-wait: 0s` — the record's defaults restated in the file the way every other block of this
      service's own settings is, so a deployment can see and override them. Green: T042.
      ⚠ **`openapi-rest-spec` must be uniquely scoped, and a test must prove it.** The filter
      resolves it as `classpath*:` + `**`/`*` + the value — a **suffix** glob over every jar on the
      classpath, not a path — so a value of `openapi.yaml` matches this service's document and any
      other `*openapi.yaml` a dependency ships, and the parser is handed whichever the glob returns
      first. Name the file and the value for this service (for example
      `yot-results-distribution-openapi.yaml`, the file renamed to match), and add a case that runs the real
      glob against the **real** test classpath and asserts **exactly one** resource matches and that
      it is this repository's. A count assertion is the only thing that catches a dependency adding
      a second document later; asserting that the parser found *a* document would pass on the wrong
      one. `PropertiesValidator` already refuses an unset value where both audit switches are on;
      it cannot refuse an ambiguous one, which is why this is a test.
      (T042 and T043 landed in one commit under the Phase 2 TDD exception, so there is no red run
      to quote. Green: `OpenApiContractTest` 8 tests, 0 failures; Checkstyle and PMD clean on main
      and test after two findings were fixed rather than suppressed - an import out of
      lexicographical order, and `getClassLoader()` where the test ruleset wants the context one.
      **The file is `src/main/resources/yot-results-distribution-openapi.yaml` and the setting matches it**,
      which is this warning carried out. Every document that named
      `src/main/resources/openapi.yaml` was re-pointed in the same commit - CLAUDE.md, README.md,
      the two rules files, three agent files - and the constitution with them, as a PATCH (5.0.3):
      Principle III names the owned document, and a principle that names a file that is not there
      is a principle a reader gets wrong. This is the one departure from FR-002's literal path, and
      it is the departure this warning asks for.
      **The contract test is a `@WebMvcTest` slice over the four real controllers, not a context
      load.** Every controller on this surface carries `@Profile("!test")`, so the profile a
      context-load test runs under is precisely the one on which none of them is registered - a
      `@SpringBootTest` here asserted an empty surface against a seven-path document and passed
      half its cases by vacuity. The slice takes the real classes, the real annotations and the
      framework's own `RequestMappingHandlerMapping`, asked for **by name** because actuator
      contributes a second one. `NotWiredController` is deliberately outside the slice: it maps the
      same three paths the generating half maps and is contributed only where that half is off, so
      exactly one of the two is ever present and the document describes the paths rather than the
      fallback.
      The `yotresultsdistribution.operations` block and the two `audit.http.*` keys landed in
      `application.yaml` as the task asks, beside the `enabled` key T005 left there and without
      restating or flipping it.)
- [x] **T044** [P] [US1] `api/OperationsAuditFactsTest` (new) — the payload carries the action, the
      outcome (status family + bounded reason), `flagOverride` on the regeneration endpoint, the run
      id, and the superseded count on the supersede endpoint; and it carries **no** request or
      response body. Driven through the publisher seam — `AuditService` is a plain class registered
      `@ConditionalOnMissingBean` (research R10), so the test context holds a capturing one. Seams:
      `api/OperationsAuditFacts` (request-scoped) and `api/OperationsAuditService`. Red: the facts
      are absent from the payload.
      **Two cases are about the publish failing, not about its content** (see Complexity Tracking,
      the audit-publish deviation): a publisher that throws on the **request** event refuses the
      call — `503` with the bounded reason `AUDIT_UNAVAILABLE`, and the application service is never
      called — and a publisher that throws on the **response** event, after the action has already
      happened, does not change the answer but logs at ERROR naming the action and the run id and
      moves a bounded counter. Neither is allowed to be a caught-and-ignored exception, which is
      what the library's own `AuditService.postMessageToArtemis` does.
- [x] **T045** [US1] `api/OperationsAuditFacts` and `api/OperationsAuditService` — the controllers
      and the advice populate the facts; the service merges them into the payload's `content` and
      delegates. Nothing else is added to the event. Green: T044.
      **`OperationsAuditService` is registered as the `AuditService`**, which the starter allows
      because its own bean is `@ConditionalOnMissingBean` (research R10), and it **does not swallow
      a publishing failure**: the library's `AuditService.postMessageToArtemis` catches every
      `Exception`, logs it and returns, which would let an operations call succeed with no audit
      event at all — condition (b) of Principle III and Principle VI both refuse that. The request
      event is published **before** the action and a failure there refuses the call
      (`503 AUDIT_UNAVAILABLE`); the response event is published after it and a failure there is
      logged at ERROR and counted, because there is nothing left to refuse. That second case is the
      residual recorded in Complexity Tracking and is the one an outbox would close.
      ⚠ **Two JMS beans that now point at the audit broker, carried here from gate round 1.** The
      audit starter's `auditConnectionFactory` is `@Primary` and its `auditJmsTemplate` is a
      `JmsTemplate`, so Boot's default `jmsListenerContainerFactory` resolves against the audit
      connection and no estate `JmsTemplate` is auto-configured at all. It is dormant today:
      `config/PublicEventsConfig` names its factory by bean name (T007), the only `@JmsListener` in
      `src/main` is `DocumentEventListener`'s and it names
      `PublicEventsConfig.LISTENER_CONTAINER_FACTORY`, no class injects an unqualified
      `JmsTemplate`, and `management.health.jms.enabled` is false. **This phase closes it**, in the
      file that is open anyway: either define the default listener factory and template against
      `jmsConnectionFactory` in `PublicEventsConfig`, or add the reflection sweep that refuses any
      `@JmsListener` in `uk.gov.hmcts.cp` which does not name `LISTENER_CONTAINER_FACTORY`. The
      invariant is currently true and pinned by nothing, which is how a listener written next year
      attaches to the audit broker with every test green.
      (T044 and T045 landed in one commit under the Phase 2 TDD exception, so there is no red run
      to quote. Green: `OperationsAuditFactsTest` 12 tests and `PublicEventsConfigTest` with the
      new sweep, 0 failures; the whole `api` package, `ConfigurationValidationTest`,
      `HttpSurfaceTest` and `TelemetryPrivacyTest` green beside them. Checkstyle and PMD clean on
      main and test after four findings were fixed rather than suppressed.
      **The two JMS beans are closed by the reflection sweep rather than by defining the default
      factory**, which is the second of the two options this note offers: the sweep refuses any
      `@JmsListener` in `src/main` that does not name
      `PublicEventsConfig.LISTENER_CONTAINER_FACTORY`, line-anchored so the word inside a javadoc
      paragraph is not read as a declaration. Defining Boot's default factory would have put this
      service in the business of building a listener container for a broker it does not consume.
      **Four things the task's sentences do not spell out and the code had to decide.**
      *The facts are a thread-local rather than a request-scoped bean*: the audit filter is a
      servlet filter publishing from the request's own thread, and a thread-local opened by the
      outermost filter and cleared in its `finally` is the mechanism `batch/RunCorrelation` and MDC
      already use here - it needs no scoped proxy on a singleton the starter constructs.
      *Which of the two publishes is the request event is kept on the facts*, because the library's
      payload does not say: both events carry the same `_metadata.name`, and the filter publishes
      exactly two, the request before the chain and the response after it.
      *The `503 AUDIT_UNAVAILABLE` is rendered by `OperationsActionFilter`*, not by the advice: the
      audit filter runs outside the `DispatcherServlet`, so a refusal it raises reaches no
      `@RestControllerAdvice`. The outermost filter catches `OperationsRefusedException` and writes
      the bounded body from the one status map, rather than letting it arrive as the container's
      own 500 with the path the caller typed in it. `AUDIT_UNAVAILABLE` is a new
      `OperationsReason`, 503 in the status map and in the OpenAPI document's `Reason` enumeration.
      *The outcome is recorded in `OperationsProblem.answering`*, the one place every refusal body
      is built, rather than at four throw sites - and a call that reached no refusal is
      `SUCCEEDED`, which is a fact rather than a gap. That removed the need for an interceptor
      reading a status code back off the response.
      **The Complexity Tracking entry's dated sign-off is still outstanding.** The plan says that
      entry "needs the design owner's dated sign-off before Phase 8 lands"; nobody was available to
      give one during this run, so the shortfall stands exactly as recorded - a response event that
      cannot be published is logged at ERROR naming the action and the run id, moves
      `yotresultsdistribution_operations_audit_unpublished`, and is dressed up as nothing else. It is
      carried to the orchestrator rather than assumed.)
- [x] **T046** [US2] `api/OperationsAuthzIT` (new) — **the real filter, wired as deployed**, with
      usersgroups stubbed at the HTTP boundary by WireMock and
      `@DynamicPropertySource` over `authz.http.identity-url-template` (the
      `LoggedInUserPermissionsResponse` body shape is in research A9/R4). Cases: a caller in
      "Second Line Support" → served, on every one of the seven; a caller in another group → `403`,
      on every one of the seven; no `CJSCPPUID` → `401`; the identity service answering `500` →
      `403` **and not `500`** (the client never throws; an outage is an empty identity, research
      R4); a forged `CPP-ACTION` naming an action the caller may not reach → still refused;
      `/actuator/health` with no headers → `200`. A test that mocks `DroolsAuthzEngine` proves the
      test, and is not acceptable here. Red: whatever the real wiring gets wrong — expect the
      `exclude-path-prefixes` list and the filter order to be the first two.
- [x] **T047** [US2] Whatever T046 exposes: the settings, the filter order, the registration. No new
      behaviour — if this task wants a behaviour change, it has found a gap in an earlier phase and
      goes back there. Green: T046.
      (T046 and T047 landed in one commit. Green: `OperationsAuthzIT` 26 tests, 0 failures;
      `OperationsAuditFactsTest` and `ConfigurationValidationTest` green beside it; Checkstyle and
      PMD clean on main and test after one finding was fixed rather than suppressed.
      **The `exclude-path-prefixes` list and the filter order were both already right**, which is
      what T046 predicted would be wrong and is the reason it is worth having: actuator answers
      with no identity, `/error` is not refused by the filter that forwards to it, and every one of
      the seven is served for "Second Line Support" and refused 403 for anybody else, 401 for
      nobody at all, and 403 for an identity service that cannot be asked. A forged `CPP-ACTION`
      and a forged vendor media type both reach the endpoint's own action, not the one they name.
      **What T046 did expose is a setting, and it is the only change T047 makes.** With the real
      publisher over a broker that is not there, every call was refused `503 AUDIT_UNAVAILABLE`
      correctly and took about **two minutes** to say so: the request event is published on the
      caller's own thread before the action, and the library ships ten initial connect attempts
      over a 2s interval with a 1.5 multiplier. That is two minutes of a held connection and a held
      servlet thread per call, on exactly the incident an operator is trying to end.
      `cp.audit.jms.initial-connect-attempts` is now 2, which says the same thing in about two
      seconds and changes nothing about what is refused. Pinned by
      `OperationsAuditFactsTest.the_shipped_configuration_should_switch_the_librarys_body_capture_off`.
      **Two things about the suite's shape, both stated rather than quiet.** The controllers are
      contributed by hand, because every one of them carries `@Profile("!test")` for the store's
      sake and a class-level profile is evaluated against the bean definition rather than the type
      - so a `@Bean` method gives the real class, the real mappings and the real derived action
      names on the profile that has no database. And the audit **publisher** is a mock while the
      audit **filter** is real: the filter's OpenAPI document, its path-parameter resolution, its
      place inside the authorisation filter and its two publishes all run. Replacing the publisher
      is the seam the starter itself offers, and a real one over a missing broker would refuse
      every case for a reason that has nothing to do with authorisation.)
- [x] **T048** [A] [US1] `api/OperationsAuditIT` (new) — one authorised call to each endpoint
      produces a request event and a response event on the seam, each carrying the caller, the
      action and the outcome, and **no** body. Records the observed result; no implementation task
      follows.
      (Observed: 7 cases, one per endpoint, 0 failures. Every authorised call leaves **exactly
      two** events; both carry the caller's `CJSCPPUID`, the action this service derived from the
      path and the method, and an `outcome`; neither carries a request or a response body.
      **The seam is the JMS template and nothing nearer**: the audit filter is real, the payload is
      the library's, and `OperationsAuditService` is the one this service contributes - only the
      template it sends through is replaced, so what is asserted is the JSON that would have left
      for the broker.
      **Two things the run had to settle.** The response event exists only where the response
      carried text - the library builds a `ResponseInfo` under `hasText(body)` - so every case
      answers something, a stubbed success where one is cheap and a bounded refusal
      (`email-output-disabled`) for the exception report, whose success shape is a tree of five
      records and whose refusal is an outcome in exactly the sense the field means. And
      `JmsAutoConfiguration` is excluded in this context: Boot's `JmsMessagingTemplate` reads a
      message converter off whichever `JmsTemplate` it finds and the replaced one has none, while
      nothing in this context publishes or consumes JMS of its own.)

---

## Phase 9: Concurrency and privacy (4 tasks)

**Purpose**: what the separate CLI JVM used to make impossible. An endpoint is served by a pod where
the consumer, both schedulers and the public-event listener are all running, and `CliModeConfig` is
not there to switch them off.

- [x] **T049** [US5] `e2e/OperationsConcurrencyIT` (new) — Testcontainers Postgres, the real store
      and the real lock. Cases: **two notify calls for one batch at once** → the dispositions
      `RegisterNotifierService`'s existing claim defines, and **no recipient is e-mailed twice**;
      **a regeneration racing the scheduled run**, in both orders — the scheduler holding the lock,
      and the regeneration holding it; **two regenerations for one date on two contexts** → the
      loser's `releaseFailed` returns no rows and its assemble meets the live-key index, surfacing
      as a **bounded recorded outcome and never an unexplained failure**; **a public document event
      arriving during a regeneration** → applied exactly as it is during a scheduled run. Red:
      expect the two-regenerations case to be an unexplained failure first — that is the finding.
- [x] **T050** [US5] Whatever T049 exposes, as a bounded refusal in the application service. No new
      locking beyond the register-generation lock, and no reproduction of a claim that already
      exists. Green: T049.
      (T049 and T050 landed in one commit. Green: `OperationsConcurrencyIT` 6 tests, 0 failures;
      Checkstyle and PMD clean on test after four findings were fixed rather than suppressed.
      **T050 changes no production code, and T049's own prediction is what it disproves.** T049
      expected the two-regenerations case to be an unexplained failure first. It is not: by the
      time the loser reads the day there is no FAILED batch left to release and nothing active and
      unbatched to assemble, so it answers a tally of noughts - `released=0`, `registers=0`,
      `batches=0` - and the day ends with exactly one new batch. The store's own claims and the
      live-key index do the arbitrating, which is what the design said they would, and adding a
      refusal for a case the store already ends cleanly would have been a second mechanism for
      something that needs none.
      **The other three races behave as designed too.** Two simultaneous notifies for one batch end
      `SETTLED` and `ALREADY_NOTIFYING` with exactly one e-mail sent. A regeneration launched while
      the schedule holds the register-generation lock does nothing and never reaches the
      regeneration service; in the other order, a contender for the same lock while a regeneration
      holds it gets nothing, and the lock is given back when the run ends. A `document-available`
      event applied to a batch an operator's run had requested moves it to GENERATED with the
      document id, exactly as it does for a batch the schedule requested.
      **Two things the run had to settle, both of them about the fixtures rather than the subject.**
      *Each case takes a register date of its own*: every one of them reads and writes a whole day -
      a regeneration releases the day's FAILED batches and re-assembles what it finds waiting - so
      two cases on one day would contend with each other's leftovers instead of with the contender
      the case is about. *And the launcher is given this machine's clock rather than the suite's
      fixed one*, because ShedLock computes `locked_until` from the instant the configuration
      carries: a fixed clock in the past writes a lock that is already expired and every contender
      walks straight through it, which is a green test over a lock that was never held.)
- [x] **T051** [P] [US4] `config/TelemetryPrivacyTest` (extend) and `support/PersonalDataMarkers` —
      **the sweep now covers responses, not only log statements**. Cases: no controller response
      record and no `ProblemDetail` field can carry a personal-data marker; `detail` never carries
      a store's or an exception's message; the `CJSCPPUID` value appears in **no** log statement in
      `api/` (it belongs in the audit event, which is the one place the caller is named on purpose);
      no response carries an unmasked address. Red: whatever the sweep finds.
- [x] **T052** [US4] The fixes T051 finds. Green: T051.
      (T051 and T052 landed in one commit. Green: `TelemetryPrivacyTest` 43 tests, 0 failures, of
      which the new `TheOperationsSurface` is 5; Checkstyle and PMD clean on test after one finding
      was fixed rather than suppressed.
      **T052 changes no production code: the sweep found nothing.** That is the finding, and it is
      worth having in the shape it has - every one of the five cases is about what an endpoint
      *could* answer rather than what one did, which is the claim a sweep exists to make and the
      one a case per endpoint cannot.
      **What the five say.** Every field of every request and response record is a bounded type,
      asserted against a **closed list** rather than against a list of forbidden ones: a response
      record gaining a `CourtRegisterDocument`, a `RegisterRecord` or a `JsonNode` is how a
      defendant reaches a caller, and only an allow-list catches the one nobody thought of. No
      field of one is named after a person. `setDetail(` appears nowhere in `api/`, so the one
      field of RFC 9457 that invites free text is never populated at all. The string `CJSCPPUID`
      appears nowhere in `api/`, so no class there holds the value it could log - the caller is
      named in the audit event and nowhere else (FR-038). And the masking rule has exactly one
      home, in `BatchListingService`, because a second rule in the adapter is how the two come to
      disagree and the weaker one ships.
      `support/PersonalDataMarkers` is untouched: the markers it holds are for a value flowing
      through a running pipeline, and these five are structural claims over the records and the
      source. Adding a marker nothing populates would have been a fixture pretending to be a test.)

---

## Phase 10: Remove the CLI (7 tasks)

**Purpose**: the deletion, last, after every endpoint that replaces a command has a passing test
(FR-051). At no commit is there neither surface. The deletion tasks carry the mechanical exemption:
a deletion has no red run, and its evidence is the green build with the replaced suite gone.

- [x] **T053** [US5] Delete `src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/batch/cli/` (all ten
      classes) and `src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/batch/cli/` (all nine suites), plus
      `src/main/resources/logback-cli.xml`. Nothing else in this commit. Evidence: the build is
      green and the test count drops by exactly the deleted suites' cases.
      (Done. The deletion exemption applies: no red run, and the evidence is the green suite with
      the deleted one gone. Green: `TelemetryPrivacyTest` 32 tests, 0 failures; Checkstyle and PMD
      clean on main and test; `compileJava`/`compileTestJava` clean under `-Werror`.
      **"Nothing else" held for the production tree and could not hold for two test files**, which
      referred to the deleted classes by type and would not compile without them. Both are
      re-pointed rather than weakened:
      *`config/TelemetryPrivacyTest`* loses its `TheOperationsCommands` group, which ran the six
      commands over doubled collaborators and swept their terminals. The claim it made - what an
      operator typed reaches no line and no answer - is `TheOperationsSurface`'s since T051, over
      the records and the source of the surface that replaced them, and
      `OperationsExceptionHandlerTest` makes it per refusal. Its `ShippedConfiguration` group drops
      `logback-cli.xml` from both parameterised cases: there is one shipped logging configuration
      now, because no JVM starts under a second one.
      *`support/GenerationLegs`* drove `ReportExceptionsCli` for the one line the sixth command
      writes. It now drives `OnDemandExceptionReportService`, which is the class that line moved to,
      and `THE_REPORT` names that class in the command's place - so the log-statement sweep's
      enumeration covers the on-demand report exactly as it covered the command.
      **Two things the re-pointing then found, and both are the enumeration working.** The sweep
      refused the new class until the drive reached *all* of its statements, so the drive is four
      invocations rather than one: the report that could not be produced, the e-mail output
      switched off, the e-mail output on with no sink behind it, and a window an operator typed
      that will not read. And the bounded-reason vocabulary did not hold `OperationsReason`,
      because until now nothing on the legs wrote one into a `reason=` slot. It is added in **both**
      of its spellings - the kebab-case wire form a `ProblemDetail` carries and the constant a log
      line writes through `Enum::toString` - which is a difference worth having recorded: the same
      refusal is spelled two ways depending on whether it is being answered or logged.)
- [x] **T054** [US5] Delete `config/CliModeConfig` and `config/CliModeConfigTest`; remove
      `yotresultsdistribution.cli` from `application.yaml`; make **every** conditional that reads it
      unconditional. **`PublicEventsConfig`'s javadoc about a CLI JVM not subscribing goes with
      it** — the rule is retired with the JVM it was about. Evidence: the build is green; a context
      still starts with the schedulers, the sweeps and the listener present.
      ⚠ **This task runs AFTER the rebase onto a merged 004, and not before.** Until 004 merges,
      `config/SchedulingConfig`, `config/SchedulingInfrastructureConfig`, `config/BatchSweepConfig`
      (004's, new), `config/IntakeSweepConfig` and `config/ProcessedLogConfig` are **004's files**
      under the coordination contract, and 004 is wiring its releaser and its batch-age sweep off
      the same CLI-mode conjunct this task deletes. Deleting the bean from under an unmerged branch
      is how both edits get lost. The ledger in `plan.md` says the same thing; if the phase order
      puts this task before the merge, it goes back to the orchestrator rather than being taken
      early.
      The enumeration is made **at that time**, against the rebased tree, because 004 adds
      consumers. As this branch stands it is: production —
      `inbound/ServiceBusConsumerConfig`, `config/SchedulingConfig`,
      `config/SchedulingInfrastructureConfig`, `config/ReportSchedulingConfig`,
      `config/IntakeSweepConfig`, `config/ProcessedLogConfig`, `config/PublicEventsConfig`; tests —
      `config/CliModeConfigTest` (deleted), `config/ReportSchedulingConfigTest`,
      `e2e/CliDispatchIT` (deleted by T055); plus 004's `config/BatchSweepConfig` and whatever suite
      proves its wiring. `batch/cli/CliMain` and `batch/cli/GenerateRegisterCli` read it too and are
      gone at T053. Every one of them is re-grepped for `CliModeConfig`, `yotresultsdistribution.cli` and
      `cliMode` after the rebase, and the list in this task is corrected in the same commit —
      a stale enumeration here is a conditional left behind on a property that no longer exists.
      (Done. The deletion exemption applies. Green: every suite under `config/` and `api/` - the
      wiring contexts, the two scheduling suites, `TelemetryPrivacyTest`, `HttpSurfaceTest` and the
      seven endpoints' - 0 failures; Checkstyle and PMD clean on main and test.
      **The enumeration, re-made against this tree, and it is nine rather than the seven this task
      predicted.** Production, every one of them a class-level
      `@Conditional(CliModeConfig.NotCliMode.class)` that is now simply absent:
      `inbound/ServiceBusConsumerConfig`, `config/SchedulingConfig`,
      `config/SchedulingInfrastructureConfig`, `config/ReportSchedulingConfig`,
      `config/IntakeSweepConfig`, `config/BatchSweepConfig` (004's, as the warning said it would
      be), `config/PublicEventsConfig`, and two this task did not predict because they were written
      after it - `config/OperationsWebConfig.GenerationBackedOperations`, which holds the
      regeneration launcher, and `api/BatchesController`, which holds the endpoint that calls it.
      `config/ProcessedLogConfig` named the condition in a javadoc and carried none, so it is a
      prose correction and not a conditional. Tests: `config/CliModeConfigTest` deleted with the
      class; `config/GenerationWiringContextTest` loses its `ACommandJvm` context and the
      `CLI_ON` property it was the only user of; `config/ReportSchedulingConfigTest`,
      `e2e/OperationsConcurrencyIT` and five production javadocs lose the paragraph about a JVM that
      no longer exists. `e2e/CliDispatchIT` is T055's.
      **`PublicEventsConfig`'s rule is retired rather than moved**, as this task requires: what
      replaces it is the statement that every JVM running this application subscribes and an
      operations endpoint must never bring up a second subscription of its own.
      **One thing worth recording about what the removal leaves.** `SchedulingInfrastructureConfig`
      and `IntakeSweepConfig` were the two configurations this condition was the *only* switch on.
      They are now conditional on the profile alone, which is what their own javadoc always said
      they should be: the gauges refresh wherever the intake half runs. Nothing else changes
      shape - every other configuration keeps the enabled-flag condition it already had.)

- [x] **T055** [US5] `docker/startup.sh` — the command dispatch, the `CLI_MAIN`, `CLI_COMMANDS` and
      `BOOT_LAUNCHER` variables and the whole `case` go; the entrypoint starts the application, full
      stop. Delete `e2e/CliDispatchIT`. `scripts/container-smoke.sh` calls `GET /operations/flag`
      through the readiness gate instead of running two commands. Evidence: the smoke passes.
      (Done. Green: `./scripts/container-smoke.sh` — `PASS: readiness reported UP within the 60s
      budget`, then `PASS: GET /operations/flag answered 200 with flag=ON`, and a clean teardown.
      **The script asserts the body as well as the status**, for the reason it asserted the printed
      line and not just the exit code before: the endpoint answers `200` for all three readings, so
      a `2xx` alone would be satisfied by an empty one. It greps `"flag":"ON"`.
      **No identity header, and that is the compose stack rather than a shortcut.**
      `docker-compose.yml` switches both estate filters off for the local loop with the reason
      written beside them, so what this step proves is the surface and the reading — the image
      serves the path at all, on the published port, through the same gate readiness answered on,
      with the deployed App Configuration reader behind it. Who may reach it is `OperationsAuthzIT`'s
      claim, over the real filter and the real rules.
      **One environmental note for whoever runs it next.** The run had to publish
      `fileservice-postgres` on no host port, because an unrelated container on this machine holds
      5433. Nothing in the script or the compose file was changed for it — the override was a
      scratch file passed in `COMPOSE_FILE` and is not in the tree — and the smoke connects to that
      database over the compose network, so the published port is for a developer's psql and not
      for this script.)
- [x] **T056** [US5] `config/HttpSurfaceTest` — re-pointed from "no controller exists" to "actuator
      and `/operations/**`, and nothing else": every mapped path is under one of the two, and no
      path submits a hearing, reads a register out or creates a batch. This is the test that stops
      the next increment from quietly adding a business endpoint.
      (Done, test-first and with a real red. Green: `HttpSurfaceTest` 15 tests, 0 failures;
      Checkstyle and PMD clean on test.
      **The red was made rather than found, and that is the point of the task.** The sweep passed
      the moment it compiled, because there is no business endpoint to find - a green sweep over a
      clean tree says nothing about whether it would notice one. So a scratch
      `@RestController` mapping `POST /hearings` was contributed to the test sources, the suite run,
      and the failure recorded: *`Expecting empty but was: ["/hearings"]`*, on
      `every_mapped_path_should_be_under_the_actuator_or_the_operations_root`. The scratch class was
      then deleted; it is in no commit. That is the assertion the task asks for - a hearing
      submitted over HTTP is noticed - demonstrated rather than asserted about.
      **The claim moved from beans to paths.** `THE_SEVEN` is a closed list of `METHOD /path`,
      spelled with the path-variable placeholder the handler mapping publishes, and the second case
      asserts the `/operations` mappings are exactly it. A closed list rather than a rule about
      forbidden shapes, for the reason every sweep in this repository gives: a rule catches what
      somebody thought of. No hearing, register-read-out or batch-create endpoint is excluded by
      matching its shape - it is excluded by not being on the list.
      **Two things the sweep had to get right to be worth anything.** It reads **every** bean of
      type `RequestMappingHandlerMapping`, not the one named `requestMappingHandlerMapping`: the
      actuator contributes a second, and asking for one bean either fails to resolve (which it did,
      first run) or, once somebody "fixes" that by name, reads the application's paths and misses
      whatever a dependency published beside them. And it spells a mapping with no method condition
      as `* /path`, because a mapping that answers every verb is a surface fact.
      The bean-level cases are **kept** beside it rather than replaced: they say which classes a
      shape of the pod holds, which is a different claim from what those classes serve. The
      enclosing class's case is re-pointed to say what it actually proves - that the `test` profile
      registers none of them - rather than to claim a surface the profile is hiding.)
- [x] **T057** [A] [US5] Full `./gradlew build` behind the `flock`: compile, the whole suite, PMD
      main and test, Checkstyle main and test, and the JaCoCo gate at the **unchanged** thresholds
      (LINE ≥ 0.88, BRANCH ≥ 0.85 — the ratchet is never loosened to admit a controller). Records
      the counts.
      (Done. `flock -w 7200 /home/sachin/.cache/yotresultsdistribution/gradle.lock ./gradlew
      jacocoTestReport check -Dtest.noFailFast=true` — **exit 0** in 4m 18s.
      **The counts.** 3867 tests, 0 failures, 0 errors, 0 skipped. Coverage: LINE 0.9765 (7019
      covered, 169 missed), BRANCH 0.9051 (2098 covered, 220 missed), INSTRUCTION 0.9768, METHOD
      0.9868, CLASS 1.0000. `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` all clean.
      **The thresholds are untouched**: `gradle/test.gradle` still reads `minimum = 0.88` for LINE
      and `minimum = 0.85` for BRANCH, and both are cleared with room — the seven controllers, the
      advice, the two filters and the audit publisher arrived inside the ratchet rather than
      against it.
      The suite is 3867 where the increment began at 3616 plus 004's: the nine CLI suites that went
      at T053 are out of it and everything Phases 2–9 added is in it.)
- [x] **T058** [US5] The documentation sweep. `specs/002-consolidate-progression-leg/quickstart.md`:
      the CLI examples replaced by the `curl` ones from this increment's `quickstart.md`. `README.md`
      and `CLAUDE.md`: the final read-through — the operations paragraphs landed in `d73ef50` and
      this is the check that nothing else in either still says "command". Then a repository-wide
      grep for `batch/cli`, `CliModeConfig`, `yotresultsdistribution.cli`, `startup.sh <command>` and the six
      command names: nothing outside this spec, the constitution's history and the earlier
      increments' own records (SC-007). **README's generation section is 004's — do not touch it.**
      (Done. Markdown and javadoc only; Checkstyle, PMD and `compileTestJava` clean afterwards.
      **`specs/002-consolidate-progression-leg/quickstart.md`.** The five-step
      `docker compose exec app ./startup.sh <command>` block is the same five steps as `curl`
      against the compose stack, with the endpoint-by-endpoint version pointed at
      `specs/005-operations-rest-api/quickstart.md`. Two things changed in substance rather than in
      spelling, and both are said in the block: no `CJSCPPUID` is sent, because the compose file
      switches both estate filters off for the local loop; and step 2's warning is inverted - the
      command held no lock and could race the 18:00 run, and the endpoint takes the same ShedLock,
      so a call landing inside the night's run is refused by the lock rather than racing it.
      **The repository-wide grep.** `batch/cli`, `CliModeConfig`, `yotresultsdistribution.cli`,
      `logback-cli`, `CliMain` and `startup.sh <command>` now appear only where the task permits:
      this spec, the constitution's amendment record, and the earlier increments' own `tasks.md`,
      `plan.md`, `research.md`, `data-model.md` and checklists. What was corrected outside those:
      `README.md`'s two "non-command JVM" phrases (there is no other kind of JVM now) and its
      quickstart line, which named the two commands the container smoke used to run;
      `.claude/agents/spec-validator.md`, whose CLI-remnant rule said "the three conditionals" and
      is nine; `src/main/java/…/batch/BatchAssembler.java`, whose javadoc named
      `batch/cli/GenerateRegisterCli` as the caller that needs its answer, now
      `application/RegisterRegenerationService`; and `src/test/java/…/support/PersonalDataMarkers`,
      whose `OPERATOR_TOKEN` note named two deleted suites as its sweepers.
      The README's increment-002 and increment-003 bullets **keep their historical sentences** and
      gain the retirement note, exactly as 004 did with the reconciler: a bullet that records what
      an increment shipped is not a description of the code as it stands.
      **The "a CLI JVM must not subscribe" rule.** Already retired in
      `.claude/rules/design_rules.md` and in `PublicEventsConfig`'s javadoc at T054, and in
      `spec-validator.md`; nothing else stated it.
      **004's two hand-off notes**, both about `batch/cli` files that no longer exist, are struck
      through and marked discharged where they stand.
      `tasks.md:2488`'s note that `GenerateRegisterCli.java:388` says "four failure reasons" where
      there are three: T053 took the sentence with the method. `plan.md`'s report row, which owed
      005 a case that a `BATCH_RELEASED` entry appears in the printed table and in the CSV: the
      printed table went with the CLI, and the CSV needs no case of its own either - checked rather
      than assumed - because `EmailReportSink` renders every entry over `entry.kind()` and every
      count over `ExceptionKind.values()`, so there is no per-kind branch a kind could be missing
      from. Where the kind itself is asserted is `ExceptionReportServiceTest`,
      `LogEventReportSinkTest` and `ExceptionReportModelTest`.
      **README's generation section was left alone**, as the task requires, beyond the one stale
      phrase the grep is for.)

- [x] **T061** [US5] `README.md` — an **Operations API** section (design owner, 2026-09-20), placed
      where the CLI paragraphs were, that a support engineer can work from without the spec: one
      table row per endpoint with method, path, request body fields, the 2xx answer and every
      refusal code it can return (`FLAG_OFF`, `FLAG_UNREADABLE`, `OVERRIDE_REQUIRES_BATCH`,
      `SCHEDULE_RUNNING`, `EMAIL_OUTPUT_DISABLED`, …) with its HTTP status; the caller group
      ("Second Line Support") and how identity reaches the pod (`CJSCPPUID` injected by the gateway,
      never trusted from a client); what is audited and what never appears in a response (Principle
      VII); the flag rule per endpoint (generate's single-batch override, supersede only while OFF
      with `dryRun`, the max age); a `curl` example per endpoint against the local compose stack;
      and the five deployment gates with the sentence that the service has no operational surface
      until they land. `CLAUDE.md` — the Message-Contract Rule names the operations API and
      `src/main/resources/openapi.yaml` as the third owned contract, the "exposes NO REST API"
      sentences are gone, and the Deployment section names `AUTHZ_HTTP_ENABLED` /
      `HTTP_AUDIT_ENABLED` (default true, switched off only by local and test configuration).
      Markdown only; exempt from the build loop but reviewed by the gate for accuracy against the
      controllers and `openapi.yaml`.
      (Done. Markdown only.
      **`README.md` gains an `## Operations API` section of its own**, where the two CLI paragraphs
      were, in six parts a support engineer can work from without the spec: who may call and how the
      identity reaches the pod; what is audited and what never leaves in a response; the
      endpoint table; the flag rule per endpoint; a `curl` example per endpoint against the compose
      stack; the five deployment gates; and the switches with their defaults.
      **The table is one row per endpoint** with method, path, body fields, the 2xx shape and every
      refusal code with its status — read off `yot-results-distribution-openapi.yaml`, the controllers and the
      application services rather than off the spec, so what is documented is what is served. The
      four the *surface* answers (`401`, `403`, `415`, `503 AUDIT_UNAVAILABLE`) are stated once
      above the table instead of repeated in all seven rows, with `501 command-not-wired` explained
      as a pod without the generating half rather than as a refusal about a request.
      **`CP_AUDIT_INITIAL_CONNECT_ATTEMPTS` is in the switches table with the reason for its
      value**, because the reason is the whole point of it: the request event is published on the
      caller's own thread before the action, so the library's ten attempts are two minutes of a held
      servlet thread before the caller is told `503`. It changes how long a refusal takes and not
      what is refused.
      **`CLAUDE.md`.** The Message-Contract Rule already named the operations API and
      `yot-results-distribution-openapi.yaml` as the third owned contract, and no "exposes NO REST API"
      sentence remained - both landed in `d73ef50`, and this task is where that was checked rather
      than assumed. What was missing is the Deployment section, which now names
      `AUTHZ_HTTP_ENABLED` and `HTTP_AUDIT_ENABLED` defaulting `true` and switched off only by local
      and test configuration, the third key `CP_AUDIT_ENABLED` that being audited actually takes,
      and the five gates. Build & Test had no CLI rows left to replace, so it gains the line that
      says where the operational surface is instead. The Cutover Rule already had the generate
      endpoint's override in place of the command's.)

---

## Phase 11: Close-out (2 tasks)

- [x] **T059** [A] After the rebase onto `main` (004 merges first, per the plan's merge order):
      re-run the `spec-validator` agent against the amended constitution, and `/speckit-analyze`
      against this spec. Gate 8 of `workflow.md` — the operations API's four conditions — is the one
      to read carefully. Records the verdicts.
      (`/speckit-analyze` run against this spec, 2026-09-21, read-only. The `spec-validator` pass is
      the gate's and is not re-run here. **Verdict: 1 CRITICAL, 1 HIGH, 3 MEDIUM, 2 LOW over 49 FR,
      12 SC and 61 tasks; coverage 60/61 (98.4%).** Gate 8's four conditions are carried — every
      endpoint has a drools allow rule, the audit filter's scope covers all seven, the flag is read
      at least as strictly as the command it replaced, and every response is bounded.
      **D1, CRITICAL, and it is a stale success criterion rather than a defect.** `SC-010` still
      reads "Start-up refuses when the operations API is enabled and HTTP audit is not". Constitution
      5.0.0 **removed** that refusal and FR-045 now says start-up MUST NOT refuse on the combination.
      The code follows FR-045; SC-010 is the only artefact still asking for the deleted behaviour,
      and it reads as an unmet acceptance gate. One sentence to rewrite, to the amendment's own
      wording: the pod starts and says so at WARN naming both settings.
      **I1, HIGH, the same shape.** `FR-048` says "the three conditionals that read it"; T054's
      enumeration against the merged tree is nine, two of them written by this increment after
      FR-048 was drafted. A reviewer checking the removal against FR-048 would check three.
      **I3, MEDIUM, and the only finding that is a code change.** Three refusals are logged as the
      enum constant while the wire carries `wire()` — `EMAIL_OUTPUT_DISABLED` /
      `email-output-disabled`, `EMAIL_OUTPUT_NOT_WIRED` / `email-output-not-wired`,
      `FLAG_UNREADABLE` / `flag-unreadable` — so an alert keyed on `reason=` and a runbook keyed on
      the body disagree. Seven other sites call `.wire()`. This is the same thing T053 hit
      empirically when the bounded-reason vocabulary had to admit both spellings.
      **G1, MEDIUM.** `FR-043` (a failure after an endpoint's side effects is not reported as a
      refusal and is not retried) maps to no task and no named test. The second half holds by
      construction; the first is asserted nowhere.
      **I2, MEDIUM.** `data-model.md` says the OpenAPI document "normalises the spelling to one
      convention"; the `Reason` schema deliberately carries two, and says so.
      **A1 and I4, LOW.** FR-029–FR-032 do not exist and nothing records why; FR-001 says "no other
      HTTP path besides Spring Boot Actuator" and omits `/error`, which T056's sweep allows
      explicitly and `OperationsErrorAttributes` exists to render.
      **Nothing was edited** — the command is read-only, and every finding above is carried into
      T060 rather than fixed here. Four of the seven are one-sentence spec corrections; one is three
      one-line code edits; none blocks the five deployment gates.)
- [x] **T060** [A] The handover note: the five deployment gates from `spec.md`, the one open item
      (the estate's audit header allowlist), and the behaviour 004 owes 005 (its pre-batching pass
      skipping operator-initiated batches). This increment **must not be deployed to STE** until the
      five gates land — the CLI is gone, so a pod without them has no operational surface at all.

---

## Handover — increment 005, operations REST API

**Read this before the increment is deployed anywhere.**

### This increment must not be deployed to STE until five gates land

The CLI is **removed**. A pod deployed without these has **no operational surface at all** — no way
to read the cutover flag, regenerate a date, resend a batch's recipients, supersede a period or pull
the exception report. And because the identity header is an assertion rather than a proof, a pod
deployed without gates 2 and 3 has a surface that is **worse than none**: any workload inside the
mesh could assert a Second Line Support identity and be believed.

None of the five is in this repository, and nothing in this repository can assert any of them:

1. **An internal ingress / APIM route for `/operations/**`** in the `cpp-aks-deploy` values, not
   exposed outside the estate.
2. **The gateway strips any client-supplied `CJSCPPUID` and injects the authenticated identity.**
   Without this, the authorisation is a caller's own claim about itself.
3. **An Istio `AuthorizationPolicy` and a `NetworkPolicy` restricting `/operations/**` to that
   gateway**, so no other workload in the mesh can reach it directly.
4. **usersgroups reachable from the pod** for the auth filter's identity client, with whatever
   network policy that requires.
5. **The Artemis audit connection** in the STE values: `CP_AUDIT_ENABLED=true` with the broker's
   hosts, port, credentials and TLS material from Key Vault, and `AUTHZ_HTTP_ENABLED` /
   `HTTP_AUDIT_ENABLED` left at their `true` defaults.

**Gate 5 is the one a values file will get wrong**, so it is worth saying twice. `HTTP_AUDIT_ENABLED`
builds nothing on its own: every `audit.http.*` bean the starter declares sits inside the
`@AutoConfiguration` class `cp.audit.enabled` gates, and this service ships that key `false` so a
laptop with no broker starts. A values file that sets the two switches and omits
`CP_AUDIT_ENABLED` serves the operations API **unaudited**, and condition (b) of constitution
Principle III is unmet. Nothing refuses that combination — start-up never refuses on a switch
combination an operator chose — so the pod **says** it instead: one WARN at start-up naming both
settings. The deployment review is what checks it.

### Open items

- **The estate's audit header allowlist.** `cp-audit-filter-springboot` captures every request
  header verbatim, and its own README says an allowlist "should be agreed with the Audit team before
  rolling this out broadly". This is an **estate decision, not a deployment step**, and it is a gate
  on rollout rather than on a values file. It cannot be closed from this repository. Owner: whoever
  takes it to the Audit team.
- **The audit RESPONSE event that cannot be refused.** The request event is published before the
  action, so a broker that cannot be reached refuses the call `503 AUDIT_UNAVAILABLE` and nothing
  happens. The **response** event has no such lever: by the time it is published the action has been
  taken, so a publish that fails leaves a request event with no response event beside it. What this
  service does is count the shortfall on
  `yotresultsdistribution_operations_audit_unpublished` and answer the caller anyway — the alternative would
  be undoing work that has already happened. **A durable outbox is what would close it**, and that
  is a design decision rather than a defect: it is recorded here **awaiting the design owner's
  sign-off**, and the counter is the evidence in the meantime.
- **The 415-before-401 ordering — now fixed, and recorded because it was live for one run.** The
  `multipart/` guard ran in `OperationsActionFilter` at `HIGHEST_PRECEDENCE`, ahead of
  `cp-auth-rules-filter` at `+30`, so an **unauthenticated** caller who declared a multipart body
  was answered `415` and never reached the authorisation filter at all. It is now
  `OperationsContentTypeFilter` at `+40`: inside the authorisation filter, so an anonymous caller
  gets `401` and a caller in the wrong group `403`, and still outside
  `cp-audit-filter-springboot` at `+50`, which is the half that has not changed — that filter hands
  a multipart request down the chain publishing neither event, and an endpoint reachable unaudited
  is an endpoint that may not exist. Pinned in `OperationsAuthzIT` on all three of `401`, `403` and
  `415`.
- **The seven findings of T059's `/speckit-analyze` pass — CLOSED in gate round 1** (below). They
  were: `SC-010` still demanding the start-up refusal FR-045 replaced; `FR-048`'s "three
  conditionals" against the nine the removal found; three refusals logged as the enum constant
  while the wire carried the kebab-case form; `FR-043` with no task and no named test;
  `data-model.md`'s claim that the OpenAPI document normalises the reason spelling; `FR-001`
  omitting `/error` and saying nothing about the vacant FR-029 to FR-032. See the round's own
  section for what each became.

### Gate round 1 — what the reviewers found and what it cost

Round 1 of the whole-increment gate. Nothing here is a new task: every change belongs to a task
already closed, and is recorded against it. No behaviour of an endpoint changed; what changed is
one log spelling, one correlation, one refusal that could not be rendered, and the coverage of
three things that were true and untested.

- **T053** — the three refusal lines that logged the enum constant now log `wire()`, and
  `TelemetryPrivacyTest`'s bounded-reason vocabulary drops the second spelling it had been widened
  with. One slot, one spelling: an alert keyed on `reason=` and a runbook keyed on the body now
  grep the same characters. (`0c1f713d`. Red: both new cases failed on
  `reason=FLAG_UNREADABLE` against `reason=flag-unreadable`; green after the three edits.)
- **T036 / T031** — a launched run's whole body is now correlated by the run id its caller was
  answered with. Only the launcher's own four lines carried `run_id`; everything the requesting
  leg, the store, the assembler and the two clients wrote during an operator's run carried no
  `runId` at all, unlike the same work under the 18:00 job. `RunCorrelation` gains an overload that
  adopts a caller-minted id. (`daaa7d7e`. Red: the new case saw `null` where it expected the
  answered id.)
- **T042 / T048** — the `503 AUDIT_UNAVAILABLE` refusal is exercised on the wire for the first
  time, for all seven endpoints, through the real audit filter: the block that renders it from the
  status map was unexecuted across the whole suite, and whether the refusal travels unwrapped out
  of `cp-audit-filter-springboot` into `OperationsActionFilter`'s catch had been observed once by
  hand and pinned by nothing. Its other half — a lost **response** event leaving the answer
  standing and moving the counter — is pinned beside it. (`bcefd5a2`, no production change.)
- **T042** — the `MessagePostProcessor` that stamps `CPPNAME`, the property the audit context
  routes on, was executed by no test: both suites mock the template. Captured and applied.
  (`031f0324`, no production change.)
- **T042** — and a real one behind it: with no open facts every publish was treated as a request
  event, so on a pod with `yotresultsdistribution.operations.enabled=false` a broker outage threw
  `AUDIT_UNAVAILABLE` out of the audit filter after the response was committed, with nothing above
  it to render the refusal. No open call means nothing to refuse: counted and said instead.
  (`2eb9291f`.)
- **FR-043, previously mapped to no task** — pinned on the three endpoints that change something,
  by a test-only filter that takes the caller away while the answer is being written. Each keeps
  the status it had decided, writes nothing, names no bounded refusal code anywhere, and leaves its
  application service asked exactly once. (`041ad920`, no production change.)
- **FR-025 against the data model** — seven success-shape assertions expected the characters the
  request sent. FR-025 was stricter than constitution Principle III(d), which bans an echo and a
  refusal naming a value, not this service's own canonical rendering of what it parsed. FR-025 is
  narrowed to say so, and the three cases now send a parseable spelling that is not the canonical
  one — `+002026-09-04`, an upper-case UUID, `…T17:00:00.000Z` — and expect the canonical answer,
  which only a parse can produce. (`974aadc1`.)
- **Principle II** — the red-run waiver 48 of these tasks ran under lived only in `spec.md`
  assumption 18. It is now in the constitution, with what it may relax, what it may never relax,
  who gives it and what is judged instead. Constitution 5.1.0. (`e8021b43`.)
- **T043** — the owned document under-described its own `ProblemDetail`: `ignoreFlag` was missing
  from the `argument` enum and `accepted`, `failed` and `state` were undeclared. Both are now
  declared, and the document is pinned against the eighteen names a refusal can carry. The pin is a
  **maintained list, not a derived one**: a nineteenth key added to a refusal and not added to the
  case passes, so the case's own message carries the obligation to change both in one commit.
  Supersede gained the no-body case its generate twin had. (`aba730e2`.)
- **T055** — `scripts/container-smoke.sh` is named in `README.md` as the un-automated release gate
  it became when `e2e/CliDispatchIT` went with the dispatch it tested. (`0be4e497`.)
- **Declined, with the reason, for the reviewers to re-judge**: removing the two unreachable advice
  handlers (`MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`), and
  binding the audit destination from the starter rather than the copied constant. See the round's
  open points. Both declines were upheld at gate round 2, one with a condition.

### Gate round 2 — the verification pass over round 1's fixes

Rounds 2 and 3 of the orchestrated gate never ran: all nine reviewer launches failed on usage
credits, and the three `(workflow)` BLOCKERs in the run's result are the harness reporting its own
reviewers missing, not a defect in this tree. The verification those rounds owed was run separately
over `0d998e6d..HEAD` — round 1's twelve commits and nothing else — and the whole suite was re-run
against them: **3886 tests, 0 failures, 0 errors, 0 skipped, `jacocoTestReport check` exit 0**.

Eight of round 1's nine items verified closed, with no regression in either of the two production
changes that carried risk: `2eb9291f`'s `facts != null && facts.firstPublish()` still refuses a
lost **request** event with `503` on a pod serving the surface (the action filter runs at
`HIGHEST_PRECEDENCE` and the audit filter at `+50`, so the request event always sees open facts),
and `daaa7d7e`'s `RunCorrelation` overload computes `owned` from the ambient value exactly as the
minting overload does, so a launcher task can neither overwrite nor clear a correlation it did not
open. What round 2 changed:

- **The constitution is back at 5.0.3, byte-identical.** `e8021b43` amended a NON-NEGOTIABLE
  principle while the increment that wrote it was the thing under review, and asserted the design
  owner's name and date on the amendment. The clause is carried verbatim, unratified, in
  `constitution-amendment-proposal.md` beside this file, and assumption 18 now says the waiver
  lives in the spec alone until the design owner rules. (`452bde27`.)
- **FR-019** claimed the requesting leg's lines carry each batch's record count. No log line has
  one — the generation service logs batch id and state, the assembler logs nothing, and the count
  exists only on `GET /operations/batches`. Said where it is instead. (`ec750794`.)
- **FR-025** admitted a parsed value on a success record only, while notify's `409` and `404` have
  always carried the batch they refused about. The rule now names that one exception and why it is
  not the value-naming Principle III(d) bans: an argument that would not read never parses, so it
  cannot reach a refusal by this route. (`ec750794`.)
- **The unpublished-audit-event counter** described one population and, since `2eb9291f`, counts
  two — a response event the caller was already answered past, and any event raised on a thread
  serving no operations call. One series, and now a description that says so. (`ec750794`.)
- **The two declined advice handlers are kept and now pinned.** The decline was upheld: they are
  unreachable only because every controller parses its own parameters as strings, and deleting them
  would turn a future `400` into a `500`. But they were unreachable *and* untested, so kept
  insurance is now tested insurance — two cases calling the advice directly, because no request
  can, asserting the `400`, the bounded reason, this service's own name for the argument, and that
  the characters that would not read reach no field of the body. (`T043`, `OperationsExceptionHandlerTest`.)
- **The audit destination decline was upheld unconditionally.** `jms.topic.auditing.event` mirrors
  the starter's own addressing, the library exposes no public constant, and a property nothing
  reads would be a fake switch.

Left for the design owner, not fixed here: the constitution proposal above, and the audit
**response** event that cannot be refused, which was already awaiting sign-off.

### What 004 owed 005, and where it stands

**Discharged in the merged tree.** 004's pre-batching pass must not fail an operator-initiated batch
that spans 18:00: a manual generation holds no run lock and has the whole requesting deadline to
work in, so judging it by the schedule's minimum age would release its rows out from under it.
`batch/StaleBatchReleaser` judges a batch the schedule made by the minimum age and a batch an
operator asked for by **the longer of that and the run's own lock duration**, and which cutoff a
batch is judged by is decided in the statement's own predicate from the `system_generated` column
`V2` already carries. 005's `application/RegisterRegenerationService` writes `system_generated =
false` (`BY_HAND`), because it is the CLI's body moved unchanged. Nothing is owed either way.

### What changed for an operator, in one paragraph

Six commands reached by `kubectl exec ... -- ./startup.sh <command>` are seven endpoints under
`/operations/**`, reached by `curl` through the gateway with the caller's own identity. The same
arguments, the same refusals, the same fields — as JSON rather than as `key=value` lines. Three
things are **stricter** than the commands were, because HTTP reaches further than `kubectl exec`
did: supersede is admitted only while the flag says OFF and gains a `dryRun` and an age bound;
regeneration takes the nightly lock instead of trusting a runbook; and every call is authorised and
audited, which no exec into a pod ever was. `README.md`'s Operations API section is the table;
`specs/005-operations-rest-api/quickstart.md` shows every refusal.

---

## Dependencies and execution order

```
Phase 0  T001                          governance, before any code
Phase 1  T002→T003, T004→T005, T006→T007      the classpath and the settings
Phase 2  T008→T009, T010→T011, T012→T013      authorisation, before any endpoint
Phase 3  T014→T015, T016→T017, T018→T019      the read endpoints
Phase 4  T020→T021, T022→T023, T024→T025      the report and the error map
Phase 5  T026→T027, T028→T029                 supersede
Phase 6  T030→T031, T032→T033, T034→T035, T036→T037   regeneration
Phase 7  T038→T039, T040→T041                 notify and not-wired
Phase 8  T042→T043, T044→T045, T046→T047, T048        contract, audit, the real filter
Phase 9  T049→T050, T051→T052                 concurrency and privacy
Phase 10 T053→T054→T055→T056→T057→T058        the removal, last
Phase 11 T059, T060                           close-out
```

- **Phase 1 blocks everything**: nothing compiles against a starter that is not on the classpath,
  and the context does not start until the component-scan clash is closed.
- **Phase 2 blocks Phases 3–7**: an endpoint with no rule is denied, so an endpoint written before
  its rule cannot be tested end to end. (The `@WebMvcTest` slices run with the filters off, so the
  slice tests themselves do not depend on it; `OperationsAuthzIT` in Phase 8 does.)
- **Phase 4's T024/T025 block nothing but improve everything after them**: the controllers in
  Phases 5–7 map refusals through the advice the report endpoint forced into existence.
- **Phase 10 depends on all of Phases 3–8**: FR-051.
- Within a phase, `[P]` test tasks touch different files and may run together; an implementation
  task never runs before the test task it is paired with is closed.

### Parallel opportunities

```
# Phase 1 - two independent pairs after T003:
T004  T006

# Phase 2 - three independent test files:
T008  T010  T012

# Phase 3 - the flag and the listing service share nothing:
T014  T016

# Phase 4 - the service, the controller and the advice are three files:
T020  T024

# Phase 6 - the service, the launcher and the run report are three files:
T030  T032  T036

# Phase 9 - the concurrency IT and the privacy sweep are independent:
T049  T051
```

## Notes

- Every `./gradlew` invocation goes behind the shared `flock`; never two Gradle builds at once in
  this tree, and never two committing agents.
- The red run recorded for a test task is a **failing assertion**. If it is a compile error, the
  seams were not landed and the task is not finished.
- `RegisteredDefectFixes` and `DifferentialAuditTest` are untouched throughout and must stay green.
  If either moves, something outside this increment's scope has been changed.
