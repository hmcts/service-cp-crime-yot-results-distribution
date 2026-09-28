# Tasks: YOT Results Distribution Service — full pipeline port, fix-first

> **Historical note (2026-09-05):** this increment is complete. Its tasks reference `doc/TECHNICAL_DESIGN.md`,
> `doc/API_CONTRACTS.md`, `doc/SOLUTION_BRIEF.md`, `doc/CHANGELOG.md` and `doc/openapi.yaml`, which were
> retired from the repo on that date — the design narrative lives on Confluence
> ([YOT Results Distribution Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004104319/Court+Register+Service)).
> The task text is left as written; `doc/DEFECT-FIXES.md` remains.

**Input**: Design documents from `/specs/001-court-register-port/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests are MANDATORY** (Constitution Principle II) and every implementation task is strictly
preceded by the test task that guards it. Test names come from the plan's test matrix — do not
rename them without updating the matrix. A fix task's test is written to **fail against the legacy
behaviour and pass against the fix**, and its DEFECT-FIXES row names it.

**Red-run convention (applies to every test task)**: a test task includes creating the minimal
**compile-safe seams** its test needs — interface declarations, record signatures, class skeletons
whose methods throw `UnsupportedOperationException` — so that the recorded red run is a **failing
assertion**, never a missing class or a compile error. The failing assertion is quoted in the test
task's commit narrative; the paired implementation task's narrative quotes the green run.

**Phase 1 bootstrap tasks are infrastructure, not TDD pairs** (constitution mechanical exemption):
their commits record verification evidence instead of a red assertion.

**[A] Acceptance/characterisation tasks** verify already-fixed behaviour (broker configuration,
assembled end-to-end behaviour, the container, the differential audit). No implementation task
follows them and no red run is required; the task records the initial observed result.

**Conventions**: package root `uk.gov.hmcts.cp.yotresultsdistribution`; production code under
`src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/`, tests under
`src/test/java/uk/gov/hmcts/cp/yotresultsdistribution/`; legacy sources referenced as `$DF` =
`cpp-context-azure-legalaidagency/azure-functions/durable-functions`. `*IT` suites need Docker and
run inside `./gradlew test`. Conventional Commits on `main`; no AI attribution. **Every task that
lands a C-fix updates that row of `doc/DEFECT-FIXES.md` (status → FIXED, pinning test confirmed)
in the same commit.**

## Format: `[ID] [P?] [A?] [US#] Description`

- **[P]**: may run in parallel with other [P] tasks in the same phase (different files, no dependency)
- **[A]**: acceptance/characterisation — see above
- **[US#]**: the spec user story the task traces to

---

## Phase 1: Bootstrap (repo, Spec Kit, ledger, contracts)

- [x] T001 Scaffold the repository from the informant reference build (gradle/, config/, .github/,
      docker/, compose, smoke script, .editorconfig/.gitignore, Application) renamed for the court
      register; first green `./gradlew build`. *(done — commit `338094e`)*
- [x] T002 Vendor the frozen outbound contract: `progression.add-court-register.json` +
      `courtRegisterDocument/*.json` at `criminal-court-public-model` **v17.103.13** into
      `src/main/resources/contracts/progression/` with `PROVENANCE.md` (tag, jar digest,
      progression `pom.xml:75` coordinate) — main resources: the schemas are the runtime pre-send
      validation authority, not test-only. *(done — commit `e83693c`; relocated from test
      resources after the P0 review)*
- [x] T003 (commit 3e8821f) Spec Kit adaptation: constitution v2.0.0 (fix-first Principle I), `.claude/`
      agents/rules/context adapted to this service, `.specify/feature.json` →
      `specs/001-court-register-port`, `CLAUDE.md` SPECKIT block. Commit with the constitution's
      SYNC IMPACT report.
- [x] T004 (commit a37080f) `doc/DEFECT-FIXES.md`: all 34 rows with fix specifications, statuses PLANNED (or
      PENDING for C18/C28/C34 with owner + trigger); `README.md` Documentation table references
      it; doc skeletons (TECHNICAL_DESIGN, API_CONTRACTS, SOLUTION_BRIEF, CHANGELOG, openapi.yaml
      comment-only).
- [x] T005 [P] Shared test fixtures cloned from IR-REPO `support/`: `PostgresTestSupport`,
      `ServiceBusEmulatorTestSupport` (mounting `docker/servicebus-emulator/config.json`),
      `RedisTestSupport`, `ServiceTestSupport` (court-register properties), `AdjustableClock`,
      `CapturedLog`, plus `application-test.yaml` (`yotresultsdistribution.consumer.enabled=false`,
      datasource excluded, readiness `ping`). Verify with a trivial context test.
- [x] T006 [P] Comparator vendored: `comparator-vectors/vectors.json`, `support/JsonParity`,
      `support/ComparatorContractTest`, `support/JsonParityTest` from IR-REPO (domain-independent;
      116 assertions). [A] — records the initial pass.

**Checkpoint**: build green; ledger exists with 34 rows; fixtures and comparator in place.

---

## Phase 2: Foundational — domain, inbound contract, persistence (blocking)

### Tests first ⚠️

- [x] T007 [P] [US1] Write `domain/DistributionCommandParserTest` +
      `domain/DistributionCommandSchemaCorpusTest` (red; seams: `DistributionCommand` record,
      parser skeleton, `config/JacksonConfig` skeleton) — six-field contract, closedness, enum
      values (`Hearing_Resulted` only), optional `userId`, the Q1a rename triple
      (`hearingDay`→ command field, `userId`→ caller identity), BigDecimal number pin; dual
      validation parser-vs-schema with the court-register `$id`.
- [x] T008 [P] [US2] Write `domain/RequestFingerprintTest` (red; seam: `RequestFingerprint`) —
      canonicalisation (uppercase-hex UUID, offset-vs-Z instants, fractional seconds), changed
      immutable field changes the hash.
- [x] T009 [P] [US4] Write `config/ConfigurationValidationTest` (red; seams: `YotResultsDistributionProperties`
      + `PropertiesValidator` signatures) — every `yotresultsdistribution.*` property/default in the plan's
      Configuration table binds; deadline < lease; renewal margin; exactly-one credential source;
      LIVE requires system-user-ids; stub-refused-when-namespace; worst-case fetch + submission
      arithmetic; `submission.validate-outbound` refused false when a namespace is set.
- [x] T010 [P] [US4] Write `config/ProcessingMetricsTest` (red; seam: `ProcessingMetrics`) — one
      case per instrument incl. `yotresultsdistribution_completions_total{reason}` for the five reasons,
      `yotresultsdistribution_deadlettered_total{reason}`, and
      `yotresultsdistribution_transformation_anomalies_total{reason}` (the C19/C20/C27 anomaly metric).
- [x] T011 [P] [US1] Write `persistence/SchemaMigrationIT` (red) — every data-model V1 fact,
      including the `processed_output` court-register columns, `UNIQUE (source, request_id)`,
      `response_code`, `anomaly_summary` (nullable text, the C19/C20/C27 bounded-count field),
      status CHECK PENDING/POSTED/FAILED, FK ON DELETE RESTRICT.

### Implementation

- [x] T012 [US1] Implement `domain/DistributionCommand`, `domain/CallerIdentity`,
      `inbound/DistributionCommandParser`, `config/JacksonConfig`,
      `src/main/resources/contracts/distribution-command.schema.json` (green for T007).
- [x] T013 [US2] Implement `domain/RequestFingerprint` (green for T008).
- [x] T014 [US4] Implement `config/YotResultsDistributionProperties` + `config/PropertiesValidator` (green
      for T009).
- [x] T015 [US4] Implement `config/ProcessingMetrics` (green for T010).
- [x] T016 [US1] Write `src/main/resources/db/migration/V1__create_processed_log.sql` per
      data-model.md (green for T011).

### Guard — tests first ⚠️

- [x] T017 [P] [US2] Write the guard PG suite (red; seams: `IdempotencyGuard`, repositories,
      domain records): `IdempotencyGuardIT` (one case per state-machine row incl. the five
      completion reasons), `ProcessedLogDurabilityIT`, `ClaimContentionIT`, `ClaimReclamationIT`,
      `StaleRunnerRejectionIT`, `CrashWindowIT`, `FailedReplayIT`, `IdempotencyCollisionIT`,
      `ProcessedOutputRepositoryIT` (digest-before-send kept after failure; POSTED replay skip).
- [x] T018 [US2] Implement `persistence/ProcessedRequestRepository`,
      `persistence/ProcessedOutputRepository`, `persistence/ProcessedLogProbe`,
      `application/IdempotencyGuard` + domain state records (green for T017; ports the informant
      SQL with the `processed_output` delta).

**Checkpoint**: state machine + schema proven against Testcontainers Postgres.

---

## Phase 3: Transport and lifecycle (US1/US2/US4 spine)

### Tests first ⚠️

- [x] T019 [P] [US1] Write `inbound/MessageListenerSettlementTest` +
      `inbound/SettlementFailureEdgeTest` (red; seams: listener + `StoreGate`) — exactly one
      settlement per delivery; COMPLETED redelivery acked without run; FAILED same-identity
      re-dead-letter; lock-loss counted.
- [x] T020 [P] [US4] Write `config/ServiceBusHealthIndicatorTest` (red; seam: indicator) — the
      broker-silence staleness model, refused settlements as fault inputs.
- [x] T021 [P] [US1] Write the emulator suite (red): `QueueSettlementIT`,
      `ContractValidationDeadLetterIT`, `DeliveryExhaustionIT`, `DuplicateDetectionIT`,
      `StoreOutageIT`, `ProlongedStoreOutageIT`, `ReadinessPolicyIT`, `StartupWithQueueDownIT`,
      `QueueOutageRecoveryIT` — against queue `yotresultsdistribution.requests` with a stub pipeline.
- [x] T022 [US1] Implement `inbound/ServiceBusConsumerConfig`, `inbound/YotResultsDistributionMessageListener`,
      `inbound/ConsumerLifecycleController`, `inbound/StoreGate` impl,
      `application/DistributionPipeline` skeleton wired to stub ports,
      `config/{DeferredFlywayMigration,IntakeStartupHealth(+Indicator),ServiceBusHealthIndicator}`,
      the port interfaces (`HearingPayloadSource`, `NowSubscriptionsSource`, `RegisterTransformer`,
      `RegisterSubmissionClient`) with their exception types (`PayloadUnavailableException`,
      `ReferenceDataUnavailableException`, `TransformationFailedException`,
      `SubmissionFailedException`) and result records (`TransformationResult`,
      `SubmissionReceipt`) per the plan's port contracts,
      `adapter/stub/*` (green for T019–T021, serialised — all touch the listener/lifecycle).
      *(done — the payload port and its stub land here, which is what the skeleton calls. The other
      three ports are deferred to the phases whose tests demand them: `RegisterTransformer` and
      `RegisterSubmissionClient` are typed in `CourtRegisterDocument`, and tasks.md gives that record
      family to **T039a**, so declaring them here would mean inventing the outbound shape ahead of
      the vendored schemas; `NowSubscriptionsSource` and its refusing stub have no caller until the
      chain is wired in **T039**, and landing behaviour with no test that could fail first is
      refused by Principle II. Recorded here so the deferral is visible rather than silent.)*

**Checkpoint**: the walking-skeleton behaviours the informant proved, re-proven here.

---

## Phase 4: Pipeline core (US3/US4 — contexts, vocabulary, dates, matching)

### Tests first ⚠️

- [x] T023 [P] [US3] Write `pipeline/DatesTest` (red; seam: `Dates`) — DT1–DT8 twins minus DT5;
      **C10 fix**: `registerDate('2020-06-01T10:00:00Z')` is the same instant (fails vs legacy
      `11:00:00Z`); **C13 fix**: ISO parse of `YYYY-MM-DD`, a catch that cannot itself throw;
      `Invalid date format` case; **C12** (`DatesTest.bst_evening_share_uses_the_share_day`):
      `sharedTime = 2020-06-01T23:30:00Z` (00:30 BST on 2 June) resolves the refdata `on=` day
      as `2020-06-01` — fails vs legacy, whose +1 h relabelling reads `2020-06-02`.
- [x] T024 [P] [US3] Write `pipeline/DefendantContextBuilderTest` (red; seams: `DefendantContext`,
      builder) — DC1–DC4, DC6–DC10 twins (register configuration `(hearing, isRegister=true)`,
      result-level tagging, `isDeleted` dropped, youth flag); **C22 fix**: a court application
      whose applicant is not a prosecuting authority is excluded (fails vs legacy); DC5 as the
      informant-contrast case.
- [x] T025 [P] [US3] Write `pipeline/VocabularyBuilderTest` (red; seam: `VocabularyBuilder`) —
      `containsOnlyKeys` the 18 real keys; custody (police/prison, application+case), appearance,
      cps, youth/adult flags; **C30 fix**: all three major-creditor predicates consistently
      unmatchable for this flow.
- [x] T026 [P] [US3] Write `pipeline/CourtExtractFilterTest` (red) — RF1 twin (result+prompt
      levels, `courtExtract` `'Y'`/`'y'` fallback).
- [x] T027 [P] [US3] Write `pipeline/RegisterBuilderTest` (red; seam: `RegisterBuilder`,
      `RegisterFragment`) — S1 twin repointed (registerDate = true instant; hearingDate
      `2020-01-20T00:00:00Z`; hearingId; 1 register defendant, 4 results, none publishedForNows);
      `courtCentreId` (correct spelling) + `courtCentreOUCode` populated; 18-key vocabulary
      attached per defendant; empty context list yields an empty fragment (C6 path).
- [x] T028 [P] [US3] Write `pipeline/SubscriptionRulesTest` (red; seam: `SubscriptionRules`) —
      SS-CR1 twin; SS-CR2 repaired (the local vocabulary actually drives the assertion);
      included/excluded NOWS + prompt/result inc-exc branches; **C4 fix**: the court-centre OU
      code feeds the court-house rule only (an `informantCode` equal to the OU code no longer
      matches — fails vs legacy); **C5**: explicit court-register branch —
      `isCourtRegisterSubscription` subscriptions match via `selectedCourtHouses`;
      `matchCpsProsecuted` not reproduced; **C30 fix at matcher level**:
      `major_creditor_flags_never_match_a_court_register` — all three major-creditor flags
      require a non-empty applicable list (fails vs legacy, where `anyMajorCreditor` is
      vacuously true on `[]`).
- [x] T029 [P] [US3] Write `pipeline/SubscriptionMatcherTest` (red; seam: `SubscriptionMatcher`) —
      CS2/CS3 twins; CS4 with a real `ouCode` lock; CS1 split: empty answer ⇒ no matches
      (`no-subscriptions` downstream), unanswered ⇒ `ReferenceDataUnavailableException`;
      **C31 fix (N17–N19)**: adult-first/youth-second hearing matches a youth-vocabulary
      subscription (fails vs legacy `[0]`-only); judicialResults still collected across all
      defendants (N18).
- [x] T030 [P] [US4] Write `application/GroupProceedingsPolicyTest` (red; seam: policy type) —
      N8–N12 under the strict-boolean rule: `true` skips with reason; `false`/`null`/absent
      proceed; `"false"`/`"true"`/`1` proceed with WARN + metric (fails vs legacy loose `==`).
- [x] T031 [P] [US1] Write `application/DistributionPipelineTest` (red) — N1–N7: stage sequence and
      argument shapes; C32 cache+fallback miss ⇒ transient; C2 a throwing stage always records a
      terminal status; the four no-op reasons N29–N33 distinguishable; C6 empty fragment ⇒
      `no-defendants`; submission invoked once per document, zero on no-op.

### Implementation (serialised where files are shared)

- [x] T032 [US3] Implement `pipeline/Dates`, `pipeline/OrderedDates`, `pipeline/HearingDates`
      (green T023) — updates DEFECT-FIXES C10/C12/C13.
- [x] T033 [US3] Implement `pipeline/DefendantContext(+Builder)`, `pipeline/Json`,
      `pipeline/JsStrings` (green T024) — updates C22.
      *(done — the gather, `DefendantContext` and `Json` land here, which is what T024 drives.
      `JsStrings` is deferred to the phase whose test demands it: its only legacy call site is
      `RecipientMapper.js:41`'s `.trim()` of a subscription email, so the trim belongs with
      **T048**'s `RecipientMapperTest`. Landing it now would mean production code with no test that
      could have failed first, which Principle II refuses. Recorded here so the deferral is visible
      rather than silent.)*
- [x] T034 [US3] Implement `pipeline/VocabularyBuilder` (green T025) — the vocabulary half of C30.
      *(done — C30 stays PLANNED with its vocabulary half recorded in the register: the fix is only
      half implemented until T037 makes the three creditor predicates consistent, and a row marked
      FIXED on half a fix is the register lying.)*
- [x] T035 [US3] Implement `pipeline/CourtExtractFilter` (green T026).
- [x] T036 [US3] Implement `pipeline/RegisterBuilder` + `domain/RegisterFragment` (green T027) —
      updates C6 (and the C26 spelling on the fragment).
      *(done — C6 moves to FIXED; C26 stays PLANNED with its fragment half recorded. C26's fix is
      the whole outbound model being honest, which is `OutboundContractValidationTest`'s claim in
      the mapper phase; the fragment's spelling is one field of it.)*
- [x] T037 [US3] Implement `pipeline/SubscriptionRules` (green T028) — updates C4/C5/C30.
      *(done — C4, C5 and C30 all move to FIXED. C30's matcher half completes the vocabulary half
      T034 landed; the legacy's per-value `FCOMP`/`CREDITOR_NAME` creditor scan is deliberately not
      ported, because both of its call sites are already guarded by the same non-empty-list test the
      fix imposes on the third predicate, and a court register's creditor lists are empty by
      construction.)*
- [x] T038 [US3] Implement `pipeline/SubscriptionMatcher` (green T029) — updates C31.
      *(done — C31 moves to FIXED. The legacy's "no subscriptions" and "no defendants" early
      returns are not written as branches: an empty set in force filters to nothing and a register
      with no defendants satisfies nothing, so both end exactly where the legacy's returns end.)*
- [x] T039 [US1] Implement the group-proceedings policy + wire `DistributionPipeline` stages
      (green T030, T031) — updates C2/C7/C32/C33 (pipeline half).
      *(done — C2 and C7 move to FIXED. C32 and C33 stay PLANNED with their pipeline halves
      recorded: C32's remaining half is the Redis/query-fallback adapter (adapter phase) and C33's
      is the transformation that chooses three of the four no-op reasons (T054). The four classified
      failure types gained a shared `domain/ClassifiedFailure` interface so the core branches on the
      classification a throw site chose rather than on a Java type — a mechanical refactor, no
      behaviour change. `PipelineConfig` still builds the walking-skeleton pipeline: the
      `RegisterTransformer` bean has nothing to be yet, and a policy bean nothing consumes would be
      dead wiring. It is widened in the same commit as T056.)*

**Checkpoint**: a hearing payload becomes a matched fragment with fixed dates, eligibility,
vocabulary and matching semantics.

---

## Phase 5: The outbound document (US3 — twelve mappers + validation)

- [x] T039a [US3] Shared compile-safe seams for the mapper phase: the `domain/CourtRegisterDocument`
      record family (document, hearingVenue, defendant, parentGuardian, hearing, caseOrApplication,
      offence, result, recipient, alias, counsel, address) with signatures matching the vendored
      schemas, plus twelve mapper skeletons throwing `UnsupportedOperationException` — so T040–T051
      stay genuinely [P] (no two test tasks create the same seam file). Infrastructure task under
      the red-run convention's seam clause; no assertions of its own.
      *(done — thirteen skeletons, not twelve: `AggregationMapper` is added alongside the twelve so
      that T051 has a seam nobody else owns, which is what this task exists for; it is marked as
      landing in T056 rather than T054, per the implementation split. Two deliberate model
      decisions, both C26: `courtRegisterCaseOrApplication`'s five never-populated fields
      (`prosecutorName`, `applicationDecision(+Date)`, `applicationResponse(+Date)`) are **not**
      declared — the record declares what the mappers write, which is what C26's fixed behaviour
      says — and every list component keeps `null` distinct from empty, because each carries
      `minItems: 1` and the `Alias`/`Counsel` absent-vs-empty asymmetry is behaviour the comparator
      guards. `DistributionPipelineTest`'s one construction of the document was widened to the new
      signature; nothing it asserts changed. `TransformationAnomaly` already carried every code
      T047/T048/T050 name, so the enum is untouched. Follow-up in the same task: the ten inline
      defensive-copy ternaries took the branch gate from 0.85 to 0.84, so the rule they all state is
      written once in `domain/FrozenList` instead of ten times — `jacocoTestCoverageVerification`
      green again, and the threshold untouched.)*

### Tests first ⚠️ (all [P] — one file each; seams provided by T039a)

- [x] T040 [P] [US3] `pipeline/AddressMapperTest` — A1/A2 repaired: absent input ⇒ absent output
      (not `[]`); address1–5 pass-through; `postcode`→`postCode`.
- [x] T041 [P] [US3] `pipeline/AliasMapperTest` + `pipeline/CounselMapperTest` — twins plus the
      pinned asymmetry: aliases `[]`⇒`[]`/absent⇒absent; counsels absent-or-empty⇒absent; name
      composition incl. middle-name-absent; unmapped `legalEntityName` asserted absent.
- [x] T042 [P] [US3] `pipeline/DefendantMapperTest` — D1–D4 twins + explicit case-first,
      applications-only-if-empty precedence.
- [x] T043 [P] [US3] `pipeline/HearingMapperTest` — **C8/C9 fixed**: multi-entry attendance selects
      the correct defendant without mutating input (fails vs legacy assignment); date-compatible
      `defendantPresent` semantics per the kernel's orderedDate rule; all three
      `defendantAppearanceDetails` renderings; absent attendance ⇒ present=false; empty array ⇒
      guarded, not a TypeError (C19-family guard).
- [x] T044 [P] [US3] `pipeline/HearingVenueMapperTest` — HV1 twin + address body (postCode case),
      lja-absent, courtCentre-absent guarded failure.
- [x] T045 [P] [US3] `pipeline/OffenceMapperTest` — OF1 twin repaired (real indicatedPlea,
      allocationDecision, convictionDate asserted); **C23 fix**: `verdictCode =
      verdictType.verdictCode ?? verdictType.categoryType` (`"1234"`, fails vs legacy
      `"desc1234"`; plus `verdict_code_falls_back_to_category_type_when_absent` for the
      code-less live-payload shape — never the description); **C24 fix**: wording
      joined with `\n`, absent legislation ⇒ wording alone (no `####`, no `undefined`); offence-level
      result scoping pinned with two offence ids (the legacy-correct behaviour kept).
- [x] T046 [P] [US3] `pipeline/ParentGuardianMapperTest` — PG1 twin (real address5), guardian
      fallback, no-parent ⇒ absent, non-string role guarded.
      *(done — fifteen cases. The legacy fixture has no fifth address line, so the twin says so
      plainly and a constructed person carries one: a repair that needed no new fixture, because the
      shape under test is one address rather than a hearing. The empty-defendant-list dereference at
      `ParentGuardianMapper.js:15` is deliberately **not** asserted here — it is C19's construct and
      C19's fix, in the mapper that calls this one, makes it unreachable; asserting a guarded answer
      for it here would be an uncatalogued behaviour change.)*
- [x] T047 [P] [US3] `pipeline/ProsecutionCaseOrApplicationMapperTest` — PC1–PC7 twins (correct
      3-arg construction), SNI-9005 case-skip parity, **C20/C21 fixes**: absent/unmatched
      application and personDefendant-less own-record are guarded skips with WARN + an
      `anomaly_summary` bounded count (`unresolvable-application:1`) — not TRANSFORMATION_FAILED
      (fail vs legacy TypeError), C22 exhibit re-asserted at mapper level, dead methods not reproduced
      (C26).
      *(done — 35 cases, 34 red on the seam. PC4–PC7 are driven through `map` rather than through
      `getCourtApplicationOffences`: the legacy's two-arg construction of a three-arg mapper is not
      expressible against this seam, and reproducing the reach-past would leave the four cases about
      an application's offences still not touching the object that gathers them. Two deliberate
      decisions recorded in the file: the SNI-9005 **case** skip keeps its shape exactly — warn and
      skip, **uncounted** — because C20 names only the application path, and the asymmetry is
      asserted so that changing it later has to be a decision; and C22's applicant gate is asserted
      at the mapper as well as at the context builder, which is what the C22 row specifies ("in both
      the context-builder gate and the mapper"). The one green case is the C26 reflection guard that
      the two dead methods are not declared — a guard against reintroduction, with nothing to fail
      against yet.)*
- [x] T048 [P] [US3] `pipeline/RecipientMapperTest` — R1–R4 twins + `cr_standard` default pinned;
      **C27 fix**: letter-delivery and missing-email drops WARN-logged and counted in
      `anomaly_summary` (`letter-delivery-dropped:n`, `recipient-missing-email:n`,
      `recipient-not-for-distribution:n`) — drop itself preserved; emailAddress2 carried.
      *(done — 26 cases, all red on the seam. The three codes divide the mapper's single `if` by the
      reason a subscription failed it: letter delivery first, then not-for-email/not-for-distribution,
      then no address to send to. **One shape is deliberately left unasserted** and is carried to
      T054 as an open decision: the legacy's `recipient.emailAddress1 !== undefined` keeps a
      recipient whose address is an explicit JSON `null`, which the frozen contract then refuses —
      so parity loses the whole register (C29) and dropping it is a behaviour change C27's row does
      not authorise. Absent and whitespace-only addresses are asserted; explicit `null` is not, and
      needs a C-number or a legacy check before T054 chooses. The trimming R3/R4 pin is asserted at
      recipient level rather than against a helper, so `JsStrings` — deferred here by T033 — is
      whatever T054 needs it to be.)*
- [x] T049 [P] [US3] `pipeline/ResultMapperTest` — RS1 twin + null/empty ⇒ absent.
      *(done — ten cases, all red on the seam. The empty-list guard is the one that matters: this
      mapper is called at three scopes where an empty filtered list is the normal case, and every
      result list on the frozen contract carries `minItems: 1`, so answering `[]` instead of nothing
      is a document progression refuses. Neither guard is exercised by the legacy suite.)*
- [x] T050 [P] [US3] `pipeline/YouthDefendantMapperTest` — YD1 twin repaired (real nationality,
      three-part name, address5); **C19 fix**: legal-entity/unmatched defendant ⇒ guarded skip
      recorded as `anomaly_summary` `unresolvable-youth-defendant:1` + the anomaly metric, not
      TRANSFORMATION_FAILED (fails vs legacy TypeError); **C25 fix**: ethnicity observed-else-self-defined
      (three branches); real + mixed `postHearingCustodyStatus`; defence-counsel filtering.
      *(done — 42 cases, all red on the seam. The YD1 twin is written **twice**: once against the
      legacy fixture, saying plainly what it does and does not carry — no `nationalityDescription`,
      no `address5`, no middle name, an empty `defendantCaseJudicialResults` — and once against
      `base/hearing-with-surviving-youth-defendant.json`, whose child has the first three of those.
      Repairing the legacy fixture in place would have destroyed the record of what the legacy suite
      actually observes. **No fixture in the repo carries a person-level `address5`**, so that one
      repair is deferred to `AddressMapperTest`'s pass-through, which already pins it. C25 is four
      branches rather than three — both, observed-only, self-defined-only and neither — because the
      last is what distinguishes the fix from always emitting something. The C19 WARN is asserted to
      carry the bounded reason and **not** the child's name or date of birth, where C20's warning
      names its application id: that row authorises an id and this one does not.)*
- [x] T051 [P] [US3] `pipeline/AggregationMapperTest` — O1/O2 repointed (reasons), O3 repaired
      (concrete `courtCentreId`; **C11 fixed fileName** exact string incl. hearingId; venue,
      recipients, youth-only defendants asserted concretely); C33 wiring.
      *(done — 22 cases, all red on the seam. C33 at this level is asserted through the **log**:
      the seam returns `CourtRegisterDocument`, so a `null` is all two different outcomes can say
      structurally, and the assertion that they are distinguishable has to be that each names its
      own `CompletionReason` code. The chain that turns those into terminal states is T056's.
      Two decisions inherited from T039a's seam javadoc and pinned here rather than left implicit:
      **all recipients dropped answers `null`** ("or no recipient", which the legacy does not do —
      it posts a document with `recipients: undefined`) and is named as the same outcome as no
      subscription at all; and the file name's court-centre code is the **hearing's**
      `courtCentre.code`, not the fragment's `courtCentreOUCode`, which is what the legacy reads and
      which no fixture could ever have distinguished because the two always agree. The
      recipients-dropped answer is worth a C-number check before T056 — it is a behaviour change
      C27's row does not itself authorise, though C33 arguably covers it.)*
- [x] T052 [P] [US3] `adapter/progression/OutboundContractValidationTest` — N25–N27: address-less
      defendant/parent and empty address1 are named violations against the vendored schemas; a
      valid document passes; the violating path appears in the bounded reason (C29, C26).
      *(done — 16 cases, all red on the seam, which this task creates:
      `adapter/progression/OutboundContractValidator`, taking the service's own contract mapper so
      that what is validated is what is serialised. The two method names the DEFECT-FIXES rows
      already name are used verbatim — `records_match_the_vendored_schemas` (C26) and
      `a_missing_required_address_is_an_explicit_failure` (C29). C26's assertion is a **fully
      populated** document rather than a structural check: with `additionalProperties: false` on
      every schema, a document carrying every field the records declare passing validation *is* the
      claim that the records and the wire agree. `field()` carries a JSON pointer
      (`/defendants/0/parentGuardian/address`) — the existing `ContractValidationException` shape
      needed no change, and the pointer is a path, never a value. One classification decision for
      T055: `minItems` violations are `INVALID_FORMAT`, because `MISSING_FIELD` is defined as absent,
      null or empty **string** and an empty array is none of those.)*
- [x] T053 [P] [US3] Author the Java fixture set under `src/test/resources/fixtures/` — legacy
      fixtures copied byte-identical where sound; the seven bad-vocabulary fixtures rebuilt with
      the 18-key set; the six new base hearings (complete courtCentre with code; surviving youth;
      group proceedings; adult-first multi-defendant; non-prosecuting-authority application;
      address-less youth). [A] — fixture authoring, no red run; provenance notes in the fixture
      README.
      *(done — twelve byte-identical copies verified with `diff`, the seven vocabulary rebuilds
      already landed with the phases that needed them, four further repairs (real indicated
      plea/allocation decision + a legislation-less second offence; offence-level results for two
      offence ids; a fragment complete enough to assemble from; a contract-valid document request),
      the six base hearings, and `support/ModelObjects` for the one fixture that is code. Observed
      result recorded in `fixtures/README.md`: `RegisterBuilder` over all six base hearings gives
      `courtCentreOUCode = B01LY00` and the unrelabelled `2020-06-01T10:00:00Z` throughout, the
      adult ahead of the youth in the multi-defendant hearing (C31), and **zero** applications on
      the defence-led one (C22). The repaired document request validates against the vendored
      schemas with zero errors where the legacy fixture fails three ways, one of them being C26's
      plural `arrestSummonsNumbers`. Three named departures from the legacy in the base hearings —
      ISO ordered dates, the complete court centre, and one dropped defendant-level result naming a
      master defendant the hearing does not carry — are recorded in the README with their reasons;
      no C-fix lands here, so no DEFECT-FIXES row moves.)*

### Implementation (serialised: T054 → T055 → T056)

- [x] T054 [US3] Implement the twelve mappers + `domain/CourtRegisterDocument` records (green
      T040–T051) — updates C8/C9/C11/C19/C20/C21/C23/C24/C25/C27 and the mapper half of C26/C33.
      *(done — thirteen bodies, not twelve. `AggregationMapper` is implemented here rather than at
      T056 because **every case `AggregationMapperTest` writes calls `AggregationMapper.map`
      directly**: T051 has no chain-level case, so there was nothing the mapper surface could not
      satisfy and nothing to carry forward. T056 keeps the wiring — `RegisterTransformationChain`,
      `RegisterTransformer` — which is what its own description asks for; the assembly itself is
      green now. The `CourtRegisterDocument` record family needed no change: T039a's signatures
      matched the schemas and the mappers wrote exactly what they declare.
      Four decisions the suites forced and this task made:
      **(1)** `OffenceMapper` answers `null` rather than `[]` for an application that gathered no
      offence, which `ProsecutionCaseOrApplicationMapperTest.an_application_with_neither_carries_no_offences`
      requires and which `CourtRegisterCaseOrApplication`'s own `minItems: 1` note already
      specified — the legacy sends `[]`, a document progression rejects. **Reversed under review
      (2026-09-01)**: that was an uncatalogued content change no register row authorised. The
      mapper keeps the legacy's `[]` and the pre-send validator refuses it as an `INVALID_FORMAT`
      — the register is lost either way, and losing it loudly is C29's whole point. See the C29
      row and `OutboundContractValidationTest.an_empty_offence_list_on_a_case`.
      **(2)** An application that is *ineligible* (non-prosecuting applicant, or another
      defendant's subject) is skipped **in silence**; only a **dangling** reference is counted as
      `unresolvable-application`. Nothing is wrong with an ineligible application's payload.
      **(3)** The C19 warning carries the bounded reason and the **hearing id** and nothing else,
      where C20's names its application id — that row authorises an id and this one does not.
      **(4)** The register day in the C11 file name is `Dates.localDate(registerDate)` — the
      court's own calendar day — not a substring of the instant.
      Two shared helpers landed rather than four copies: `JsStrings` (the filter-on-truthiness name
      join the counsel, parent-guardian and youth-defendant mappers each spell out, plus the
      recipient trim and C24's wording join) and `Json.elements`, which is `Json.array`'s iteration
      rule for a caller already holding the array; `Json.array` delegates to it and no behaviour
      moved. `pmdMain` was **red at the start of this task** with six violations and is green at the
      end: four were the seam fields these bodies now use, and the two genuinely pre-existing ones
      (`YotResultsDistributionProperties` duplicate `@DefaultValue` literal, `NoRegisterReason` field/method
      name) took narrow inline suppressions with reasons. `OutboundContractValidator`'s unused
      `json` field carries a suppression that says it comes off with T055's body.)*
- [x] T055 [US3] Implement `adapter/progression/OutboundContractValidator` (green T052) — updates
      C29 (validation half) and C26 (schema authority).
      *(done — 16 red cases green, and both rows move to FIXED. Four decisions the schemas forced:
      **(1)** the root is `progression.add-court-register.json`, not `courtRegisterDocumentRequest`
      — the command is what this service POSTs, and the two differ (the request declares
      `defendantType` and `courtApplicationId`, which no register carries). **(2)** Every
      `http://justice.gov.uk/…` identity the contract `$ref`s is mapped explicitly to its vendored
      copy under `classpath:contracts/progression/`, with `preloadSchema` on: an unmapped identity
      fails when the validator is constructed, because a validator that silently degraded to "could
      not fetch the schema, so nothing was checked" would reinstate the exact blind spot C29 exists
      to close, and one that reached the network on the hot path would be worse. **(3)** The dialect
      is DRAFT_4 — the vendored schemas declare draft-04 and spell their identity `id`, not `$id`,
      so reading them as draft-07 would leave every reference unresolvable. Format assertions are
      **on**, which is draft-04's own reading and progression's. **(4)** Where a document breaks more
      than one rule the reason is chosen by sorted pointer rather than by the validator's traversal
      order, so one document always yields one reason, and the shorter pointer of a nested pair —
      the outer failure — wins. The `minItems ⇒ INVALID_FORMAT` classification T052 specified is
      written down where the mapping lives, with its reason. The seam's `PMD.UnusedPrivateField`
      suppression came off with the body, as it said it would.)*
- [x] T055a [US3] Write `pipeline/RegisterTransformationChainTest` (red; seam:
      `RegisterTransformationChain`) — fragment → matched subscriptions → validated document
      through the chained stages; a no-op at each stage surfaces its distinct reason; stage
      exceptions classify, never swallow.
      *(done — 20 cases, all red on the seam this task creates. Driven through the **real**
      collaborators over the six base payloads rather than through mocks: what the suite is about is
      the joins, and a mocked stage cannot get a join wrong. Three decisions recorded here:
      **(1)** the C6 case asserts `no-defendants` **and** asserts it is not `no-subscriptions`,
      because a register with no defendants satisfies no subscription either — asking the questions
      in the aggregation's own order would answer `no-subscriptions` for it, so the chain's first
      stage is what makes C6 real and the negative is what pins it. **(2)** The contract check is a
      chain stage, so a document progression would refuse leaves the chain as a **classified**,
      non-transient `TransformationFailedException`; the reason is `OUTBOUND_CONTRACT_VIOLATION`,
      the code `data-model.md` already reserves for the C29 pre-send check, rather than the generic
      `TRANSFORMATION_FAILED` — the pipeline's catch-all would otherwise read it as an unexpected
      TRANSIENT failure and hand the delivery back four more times for a document that reads the
      same every time. **(3)** The envelope guards are the chain's own: a payload carrying no
      hearing reaches `Json.dereferenced` today and fails with a message about `courtCentre`, and
      one carrying no shared time throws a bare `NullPointerException`, so both are asserted as
      classified failures and the chain has to say what it means.)*
- [x] T056 [US3] Implement `pipeline/RegisterTransformationChain` + `pipeline/AggregationMapper`
      wiring into `RegisterTransformer` (green T055a and T051 chain cases;
      `DistributionPipelineTest` end-to-end unit path now green). *(T051's cases are already green —
      `AggregationMapper`'s body landed with T054 — so what remains here is the chain and the
      transformer wiring, not the assembly.)*
      *(done — T055a's 20 cases green, and C6 moves to FIXED. `RegisterTransformationChain`
      implements the `RegisterTransformer` port over four stages: build, address, assemble, hold to
      the contract. Three decisions, and one thing this task deliberately did **not** do:
      **(1)** the chain asks the **gather** question before the subscription question, which is the
      opposite of `OutboundCourtRegister/index.js:17` before `:22` and is the whole of C6 — a
      register with no defendants matches no subscription either, so the legacy's order answers
      `no-subscriptions` for a hearing whose outcome is `no-defendants`. The aggregation keeps its
      own order and its own guards, because it is called directly by its suite and a stage that is
      only safe when its caller asked first is not safe; the two sites read the same flag on the same
      fragment and cannot disagree.
      **(2)** `AggregationMapper.map` was left exactly as T054 wrote it, returning `null`. Giving it
      a richer return would have been tidier for the chain and would have left `map` a wrapper called
      only from its own tests — production code with no production caller, which is the class of
      thing C26 exists to refuse. The chain names the remaining two outcomes itself instead: handed a
      non-empty match, the aggregation's only remaining answers are the youth filter and C36.
      **(3)** `TransformationFailedException` gained a second constructor taking a `ReasonCode`. The
      classification stays fixed — every transformation failure is non-transient — but a document the
      frozen contract refuses is `OUTBOUND_CONTRACT_VIOLATION`, the code `data-model.md` already
      reserves for the C29 pre-send check: support acts on it differently, because what is wrong is
      the register rather than the hearing.
      **What was not done: `PipelineConfig` is not widened.** The pipeline's full constructor needs
      `NowSubscriptionsSource` and `RegisterSubmissionClient`, and neither exists until T066/T067 —
      so wiring the transformer bean now would take every run past the skeleton's early completion
      and into an NPE on the ports that are still null, and declaring the transformation beans
      without wiring them is the dead wiring T039 refused for the same reason. The e2e ITs start the
      real context (they carry no `test` profile), so this is not hypothetical. The final wiring is
      T068a's task; this note is here so the omission is visible rather than silent.
      **Overturned under review (2026-09-01)**: leaving it undone left the deployed graph as the
      walking skeleton, which settles every message having produced nothing, and no suite could
      say so. The wiring landed early — see T068a — with the two absent ports served by stubs
      chosen and refused at startup, and `config/PipelineCompositionTest` as the test that fails
      whenever the assembled service stops producing a register.)*

**Checkpoint**: fragment → validated outbound document, all mapper fixes pinned.

---

## Phase 6: Live adapters (US1 — payload, refdata, submission)

### Tests first ⚠️

- [x] T057 [P] [US1] `adapter/payload/HearingPayloadCacheKeyTest` +
      `adapter/payload/CachedHearingPayloadAdapterTest` — dated key first then undated twin,
      cache-then-query order, RedisException-scoped absorb (a cache outage still asks the query
      side), query asked exactly once on cache hit = never.
      *(done — 22 cases red against four seams: `HearingPayloadCacheKey`, the `HearingPayloadCache`
      and `HearingPayloadQuery` ports, and `CachedHearingPayloadAdapter`. Two decisions the seams
      settle. **(1)** `HearingPayloadQuery.fetch` answers `Optional`, and empty means the query side
      answered and held none — a 404 or the empty-bodied 200 the results context serves for a
      hearing it does not have. A read that could not be made raises instead, so the one participant
      that knows *both* sources missed is the one that classifies it, which is what makes C32's
      named test `a_double_miss_is_transient_never_silent` a test of this adapter rather than of the
      client underneath it. **(2)** The RedisException-scoped absorb is the cache adapter's, not
      this one's: from here a cache that is down and a key that is absent are the same empty read,
      and the suite pins both halves — the query side is still asked, and a failure that is not the
      cache's own is not absorbed even when it happens to be a `RedisException` that escaped its own
      handler.)*
- [x] T058 [P] [US1] `adapter/payload/ResultsQueryHearingPayloadClientTest` (WireMock) — K1/K4/K6
      twins repaired (real base URI, media type, CJSCPPUID); K2 repointed to the retry policy;
      K3 repaired (absent cjscppuid outcome asserted); **C32**: empty body / 404 ⇒
      `PayloadUnavailableException` (transient), never silence.
      *(done — 30 cases red against the `ResultsQueryHearingPayloadClient` seam. Three things the
      suite decides. **(1)** K8's `?hearingDate=` query string is not twinned and its absence is
      asserted instead: that form belongs to the `EXT_` endpoint (`index.js:63-67`), and the `INT_`
      entry this flow uses takes the hearing id alone. **(2)** C32's two silences are told apart
      rather than merged — a query side that answered and held nothing (empty body, `{}`, 404) is
      an empty answer, and it is `CachedHearingPayloadAdapter` that turns it into a transient
      failure once the cache has missed too, which the `DoubleMiss` nest proves over real HTTP; a
      read that could not be made at all (exhausted retries, a refusal, a body that is not JSON)
      raises in the client. **(3)** K3's repair is the branch it was written over: `index.js:176-178`
      drops the run when no `cjscppuid` was supplied, so the twin asserts that a run with no
      identity anywhere is a recorded transient failure and that no unauthenticated request is sent.
      `retry_taxonomy_matches_the_submission_client` is a parameterised case over 408/429/5xx, with
      its counterpart over 400/401/403/422 — the fixed C3 taxonomy, and the one T061 will hold the
      progression gateway to.)*
- [x] T059 [P] [US1] `config/LivePayloadConfigTest` — **C15**: TLS verification on
      (`TransportSecurity`); C14 retirement asserted (no legacy retry env vars bound).
      *(done — 11 cases, 4 red against the `LivePayloadConfig.cacheUri` seam. The seam is the
      configuration class carrying that one static method and no `@Bean` yet: the payload beans
      belong to T065, and declaring them here would put a throwing bean in the default-selected
      configuration every context test boots. **The two halves are not the same kind of test, and
      the difference is worth stating.** C15 is a fix, so it goes red and then green. C14 is a
      *retirement* — a claim that four settings which do nothing have nowhere to bind — and a claim
      about an absence cannot go red without first adding the thing it denies; those three cases
      pass on arrival and are characterisation, pinning the shape so the knobs cannot creep back.
      They are written two ways for that reason: the settings record's components are named exactly,
      and an environment still exporting `max-retries`, `total-retry-time-in-ms`,
      `number-of-attempts` and `reject-unauthorized` is shown to bind to a record indistinguishable
      from one that never saw them.)*
- [x] T060 [P] [US1] `adapter/refdata/ReferenceDataNowSubscriptionsClientTest` (WireMock) —
      `on=` day derived from the fixed registerDate (C12); unanswered/5xx ⇒ transient; empty set
      returned as empty (matcher decides `no-subscriptions`).
      *(done — 28 cases red against the `ReferenceDataNowSubscriptionsClient` seam. Three notes.
      **(1)** C12's derivation is `Dates.subscriptionDay`'s and is pinned by
      `DatesTest.bst_evening_share_uses_the_share_day`; what this suite owes the fix is that the
      client derives nothing of its own, so the cases build the day with the real `Dates` from a
      23:30 BST share and assert the parameter carries `2020-06-01` and never `2020-06-02`. A second
      derivation appearing in the adapter is what these would catch and nothing else would.
      **(2)** A `404` is a refusal here, where it is an empty answer in the payload client. The
      now-subscriptions resource always exists; a 404 on it is a misconfigured path, and reading
      that as "nobody is subscribed" is precisely the substitution this fix ends. The two clients
      differ on that one status and agree on the rest of the C3 taxonomy.
      **(3)** The mesh headers are configuration because the authorisation scheme is not documented,
      so the suite pins that a configured `Accept` or `CJSCPPUID` *replaces* the contract value
      rather than joining it — two values of either is a 406 or an ambiguous caller.)*
- [x] T061 [P] [US1] `adapter/progression/ProgressionCommandGatewayTest` (WireMock) — N34–N45:
      202-only; 400/401/403/404/422 park; non-202 2xx = `SUBMISSION_NOT_ACCEPTED`; 408/429/5xx/
      connect retry with exponential backoff; `Retry-After` delta-seconds bounded, HTTP-date
      classified not parsed; exhaustion → FAILED + `exhausted_message_id` (C1, C3).
      *(done — 41 cases red against the `ProgressionCommandGateway` seam and its `SubmissionPause`.
      Three things the suite settles. **(1)** `SubmissionFailedException` now carries the status
      progression answered, as an `OptionalInt`. `processed_output.response_code` is half of C1 — the
      column that turns "the register did not go" into "progression answered 400" — and the throw
      site is the only participant that knows which; empty is a real answer, because a connect
      failure has no status and a row carrying an invented one would say an attempt was answered
      when nothing answered. A status is also all that may cross that boundary: it is bounded and
      says nothing about a child, where a response body from this command can name one.
      **(2)** N42 is split between two participants and the suite says so rather than claiming the
      whole row: this class hands back TRANSIENT with the last status, and the `FAILED` row plus
      `exhausted_message_id` are the guard's on the last permitted delivery, already pinned by
      `DeliveryExhaustionIT`. **(3)** N44 (store unavailable ⇒ abandon and suspend intake) is
      deliberately not here — it is decided before a document is ever assembled, by `StoreGate`, and
      is pinned by `StoreOutageIT`/`ProlongedStoreOutageIT`; a gateway case for it would assert the
      pipeline's behaviour through the wrong object. The endpoint and identity startup checks the
      informant gateway makes are `PropertiesValidator`'s here, so one deployment fault has one
      home.)*
- [x] T062 [P] [US1] `adapter/progression/ProgressionRegisterSubmissionClientTest` — P1 twin fixed
      (URL, media type, real CJSCPPUID, digest written before send, response_code recorded).
      *(done — 16 cases red against the `ProgressionRegisterSubmissionClient` seam, and the seam
      needed the submission port widened. **The port carried too little to write the row it is
      supposed to write, and this is where that showed.** `ProcessedOutputRepository` takes no key:
      every statement selects the row from `processed_request` under this run's `claim_owner` and
      `claim_token`, so a superseded runner cannot replace the digest of the body the winner is about
      to send. An adapter handed only `(document, caller, anomalies)` has nothing to fence against —
      and two of the row's own columns, the court centre's OU code and the register day, are not
      recoverable from the frozen `add-court-register` body either: the OU code appears only inside
      the file name, and re-deriving the day in the adapter would be a second derivation of the day
      C12 exists to fix. So `submit` now takes one `application/RegisterSubmission` — claim,
      document, OU code, register day, caller, counts — the shape the informant port's
      `AuthoritySubmission` has minus the fan-out dimension this flow does not have.
      `TransformationResult.Register` gained the OU code beside the document, sourced from the
      fragment the register was addressed by, because the transformation is the one participant that
      holds it. **`SubmissionReceipt` gained `sentByThisDelivery`** for the same reason: a POSTED
      replay skips the POST and still completes `submitted`, and a receipt that could not tell the
      two apart would have the run log a call it never made. The existing pipeline and composition
      suites were carried across unchanged in meaning and stay green.)*
- [x] T063 [P] [US1] `adapter/payload/LettuceHearingPayloadCacheIT` (Redis container) — live
      GET/dated-undated forms, TLS options honoured.
      *(done — 16 cases red against the `LettuceHearingPayloadCache` seam, which reaches the real
      server through `HearingPayloadCacheKey`'s seam as well. Two notes. **(1)** Every case writes
      under the **literal** key the producer publishes and reads through the key builder: the
      producer writes those keys and this service only guesses at them, so a suite that wrote through
      the builder too would agree with whatever the builder does and pass while production read
      nothing. The dated and undated forms are asserted as **two keys**, including that an undated
      payload is *absent* from the dated key — which is why the adapter tries one and then the other
      rather than choosing. **(2)** C15 is asserted by connecting rather than by reading a setting
      back. `LivePayloadConfigTest.transport_security` pins that the URI asks for a verified
      certificate; what it cannot show is that the client acts on it, so here a cache configured for
      TLS is pointed at the plaintext container and must fail to read — and fail as a miss, like any
      other cache outage. The container stays plaintext deliberately: one carrying a self-signed
      certificate would only prove a suite can be told to trust one.)*
- [x] T064 [P] [US2] `application/SubmissionRedeliveryIT` (PG + WireMock) — POSTED replay skips the
      POST; PENDING/FAILED replays re-attempt.
      *(done — 5 cases red over a real Postgres, a real repository and a real socket. The suite
      exists because the two unit suites cannot fail together: the repository IT proves the
      statements and the adapter test proves the ordering against a **mock** of the very decision
      under test, so "already POSTED is skipped" is asserted there against a stub of itself. All
      three of an output row's states answer a replay differently and all three are here — POSTED is
      terminal and the POST is skipped, FAILED is re-claimed and re-sent, and PENDING (a runner that
      died between claiming the row and learning the outcome) is re-sent too, because that row is
      evidence a POST may have been made and a duplicate progression's sweep absorbs is preferred to
      a loss nothing does. The fifth case is the fence end to end: a runner whose claim was reclaimed
      while it worked selects no row, so it never posts at all — which is what stops one hearing
      acquiring two registers when a lease lapses under a slow run.)*

### Implementation

- [x] T065 [US1] Implement `adapter/payload/*` (Lettuce cache, query client, cached adapter) +
      `config/LivePayloadConfig`/`StubPayloadConfig` (green T057–T059, T063) — updates
      C14/C15/C32.
      *(done — 79 cases green across the four suites, and C14, C15 and C32 all move to FIXED. Three
      things the implementation decides. **(1)** The client raises for every failure and answers
      empty for every answer, and a `404` is the one status that crosses that line: the resource is
      per-hearing, so its absence is the query side saying it does not hold this hearing, exactly as
      the empty-bodied `200` the results context also serves — while a body that will not parse is a
      failure, because a gateway error page served with a `200` is not an answer about a hearing.
      **(2)** The retry taxonomy is written as the positive list (408, 429, 5xx, connect and read
      failures) rather than as the legacy's `status <= 429` cut-off, so the two statuses that most
      plainly mean "ask again" stop being the least-retried failures in the estate — and it is the
      same list the progression gateway applies. **(3)** `StubPayloadConfig` needed no change: it
      already serves the port and startup already refuses it where the deployed credential source is
      in use, so the live beans landing beside it changes nothing about how one of the pair is
      chosen.)*
- [x] T066 [US1] Implement `adapter/refdata/ReferenceDataNowSubscriptionsClient` + configs (green
      T060).
      *(done — 28 cases green, and `config/LiveSubscriptionsConfig` is the LIVE half of the pair
      `StubSubscriptionsConfig` was already the other half of. Three notes. **(1)** Every status
      reference data answers with is a failure here, `404` included — the resource always exists, so
      a `404` on it is a misconfigured path, and this is the one status on which this client and the
      payload client deliberately differ. **(2)** The mesh headers are applied first and the two
      contract headers set over them, so a header configured under a contract name replaces the
      contract value rather than joining it. **(3)** The startup rules the composition remediation
      put in place still hold and needed no change: `PropertiesValidator` refuses `STUB` wherever the
      deployed credential source is in use and beside a LIVE payload source, which
      `ConfigurationValidationTest` re-proved on this run — the live bean landing beside the stub
      changes which of the pair is chosen, never whether the refusals apply.)*
- [x] T067 [US1] Implement `adapter/progression/{ProgressionCommandGateway,
      ProgressionRegisterSubmissionClient}` (green T061, T062, T064) — updates C1/C3 and the
      submission half of C29.
      *(done — 62 cases green across the three suites, and C1 and C3 move to FIXED with C29's
      submission half noted. Three things worth stating. **(1)** The retry taxonomy is written as the
      positive set rather than the legacy's cut-off, and it is now asserted identically in all three
      clients; the one deliberate disagreement is `404`, which is an empty answer from the payload
      query and a refusal from the now-subscriptions read. **(2)** `config/LiveSubmissionConfig` is
      chosen by the **payload** mode, exactly as `StubSubmissionConfig` is, because that is the
      discriminator `PropertiesValidator` already reasons with — "a local stub run never fetches a
      hearing, so it never reaches the POST at all" — and a second mode property would give an
      operator two switches for one sentence. **(3)** The skipped-POST receipt carries no status:
      `SubmissionReceipt`'s `responseCode` is an `int` and cannot express absence, so it is zero and
      `sentByThisDelivery` is what says nothing was sent — the pipeline logs both together, and a
      202 there would have the run report a call it never made.
      **Partially-flipped rows reviewed**: C32's adapter half landed at T065 and the row is now
      FIXED; C33 was already complete at T056 and needed nothing here; C29's submission half is this
      task's and is recorded, and what stays open on that row is the business fallback (Q4), which is
      a decision rather than an implementation.)*

**Checkpoint**: all live adapters proven against WireMock/containers; every C-fix landed except
documentation-final states.

---

## Phase 7: Assembly, e2e, docs (US1/US4)

- [x] T068 [US4] Write `HttpSurfaceTest`, `SharedObjectMapperTest`, `TelemetryPrivacyTest`
      (red; the privacy test red on a seeded violation) — actuator-only surface; no PII at INFO+.
      *(done — 22 cases, and the red is one seeded line rather than a missing implementation, which
      is what this task's own text asks for. Three things worth stating. **(1) Two of the three
      suites passed on arrival and are recorded as characterisation, not as red runs.**
      `HttpSurfaceTest` reads the actuator's own link list — the surface an operator, a scanner and
      an attacker all see — and finds exactly `health, info, metrics, prometheus`; the eleven
      endpoints a dependency could publish all 404. `SharedObjectMapperTest` pins the *injected*
      mapper rather than the static factory, which is the assertion `JacksonConfig`'s own note asks
      for and the only one that would notice the running service reading money as binary floating
      point while every unit suite stayed green. Neither could have gone red honestly: they claim
      what is already true, and the tasks.md rule is to investigate and record rather than to claim
      a red.
      **(2) The privacy suite passed on arrival too, so the violation was seeded** —
      `AggregationMapper`'s assembly line was made to log the register's defendant names, the exact
      shape of the mistake somebody makes while debugging a register with the wrong people on it.
      The recorded red:
      `[a child's own details reached the log index: CHILDNAMEMARKERZQX7] Expecting no elements ...
      to match given predicate but this element did: "Outbound court register assembled.
      hearingId=1828f356-… defendants=1 recipients=1 names=[CHILDNAMEMARKERZQX7 Duncan
      CHILDNAMEMARKERZQX7]"`. The seed is production code and is removed by T068a.
      **(3) The register is really assembled under the capture.** The suite drives the listener over
      the bean graph `PipelineConfig` builds, doubling the four outward ports only, so the fragment
      builder, the matcher, the twelve mappers and the contract validator each get to write whatever
      they write about a child whose every personal field is a marker — name, address, NINO, contact
      email, ethnicity, date of birth, the guardian's name and the statement of facts. A suite that
      mocked the transformation would have proved nothing about the twelve classes that hold those
      fields. Beside that: correlation on every INFO+ line, the caller identity, the producer's
      message id, field name and body, a transport fault's words, a refused settlement's words, an
      adapter failure's words, the broker credential, and two claims about the shipped configuration
      — `logback.xml` emits the MDC, and `application.yaml` turns no logger below INFO.
      `support/NowSubscriptionFixtures` is extracted from `PipelineCompositionTest` so the two
      suites cannot come to disagree about what a matching subscription is.)*
- [x] T068a [US4] Implement `config/PipelineConfig` + the final `application.yaml`
      (green for T068; the logging swept against the privacy test).
      *(partly done, ahead of its phase — 2026-09-01 review. `PipelineConfig` now builds the real
      bean graph: dates, the group-proceedings policy, the fragment builder, the subscription
      matcher, the contract validator and `RegisterTransformationChain` behind
      `RegisterTransformer`, with the pipeline over all five ports and the cumulative deadline
      unchanged. `config/PipelineCompositionTest` drives that graph out of a Spring context over
      doubles for the four outward ports only — schema-invalid ⇒ FAILED with no submission,
      every-recipient-dropped ⇒ COMPLETED no-subscriptions with no submission, happy path ⇒ one
      submission. The two ports whose live adapters land at T066/T067 are served meanwhile by
      `adapter/stub/StubNowSubscriptionsSource` (an empty answer, refused at startup beside a LIVE
      payload source) and `adapter/stub/StubRegisterSubmissionClient` (a refusal, chosen by the
      payload mode). What remains for this task: the final `application.yaml` and the privacy
      sweep against T068's tests.)*
      *(done — the seed is out and all 22 of T068's cases are green. Four things this task settled.
      **(1) The bean graph needed no widening.** Every port the pipeline takes now has a real
      adapter behind it, and none of them is declared here: each is a pair of configurations chosen
      by a mode — payload by `yotresultsdistribution.payload.mode`, subscriptions by
      `yotresultsdistribution.referencedata.mode`, submission by the payload mode, the processed log by
      `ProcessedLogConfig` — with `PropertiesValidator` deciding which of each pair may be chosen.
      What this task did to `PipelineConfig` is say so: its class note still described the live
      adapters as arriving "later", which had stopped being true at T066/T067, and a configuration
      whose comment describes a graph that no longer exists is the next reader's wrong turn.
      **(2) `yotresultsdistribution.submission.validate-outbound` reads nothing, deliberately, and now says
      so.** The key binds and startup refuses `false` where the deployed credential source is in
      use — which is all `ConfigurationValidationTest` and the plan's configuration table ever asked
      of it — but `PropertiesValidator`'s own javadoc claimed local runs could switch the check off
      "so a fixture can reach the wire", and nothing implements that. The claim is corrected rather
      than the behaviour: honouring the flag would put a production bypass in front of C29, the fix
      that turns a swallowed 400 into a recorded failure, and the suite that needs an invalid shape
      on the wire has one over WireMock in `ProgressionCommandGatewayTest`. Recorded here because it
      is a decision, not an omission — the alternative reading is a one-method change.
      **(3) `application.yaml` is final.** `yotresultsdistribution.referencedata.headers` was the one setting
      the file never mentioned, bound since T066; it is documented beside its progression twin,
      including that a header configured under a contract name replaces the contract value. The
      retry-policy keys renamed in the shared-`RetryPolicy` remediation were already correct here
      and their budget arithmetic was re-checked against `PropertiesValidator` (69s + 49s + 75s +
      the fixed 30s margin against a four-minute deadline). The `logging:` block gains the note
      saying nothing below INFO may be shipped, which T068's suite now enforces.
      **(4) The sweep found nothing to change in the code.** Every log line in the service was read
      against the privacy suite: counts, bounded codes, `type=` for anything somebody else wrote the
      text of, and the permitted correlation set. One test change came out of it — the shipped-level
      assertion now drops comment lines before matching, because a block that documents why nothing
      here may be set to DEBUG is the opposite of the block it refuses, and a matcher that could not
      tell the two apart would punish the file for explaining itself.)*
- [x] T069 [A] [US1] `e2e/YotResultsDistributionEndToEndIT` — message → POST(202) → COMPLETED `submitted`,
      `processed_output.status = POSTED`; one case per no-op reason; runs the quickstart sequence.
      *(done — five cases, all green on introduction and recorded as the observed result rather than
      claimed as a red run. Four things this task settled.
      **(1) Nothing between the queue and the socket is doubled.** The context is the shipped one, the
      payload is read out of a Redis container under the key `HearingPayloadCacheKey` builds, the
      subscriptions arrive over HTTP, and the register is serialised, validated against the vendored
      schemas and POSTed. `PipelineCompositionTest` proves the same bean graph with its four outward
      ports doubled; what it cannot see is whether the adapters behind those ports agree with it, and
      that is the only part of this that fails in a deployment.
      **(2) One WireMock server is all three contexts.** results-query, reference-data and progression
      have disjoint paths, so `support/RegisterStackSupport` runs one server, one lifecycle and one
      reset — and that is what makes "no POST was made" assertable at all, since it is a claim about
      the single server that would have received it. Every context answers politely by default (an
      empty hearing, nobody subscribed, 202) because the emulator queue is shared with every other
      `*IT`: a neighbouring suite's message reaching this consumer completes `no-defendants` and
      leaves, rather than being retried to the dead-letter queue by a suite it has nothing to do with.
      **(3) The happy path is asserted as far as the digest.** `processed_output` is POSTED carrying
      `response_code = 202`, the court centre, `register_date = 2020-06-01` (the day the recipients
      were read for, C12) and a `request_digest` equal to the SHA-256 of exactly the bytes WireMock
      received — which is the fact reconciliation needs and the one C1's legacy has no way to record.
      **(4) `no-youth-defendants` is only reachable through a subscription that matches.** The youth
      filter runs a stage after the addressing, so an adult-only hearing has to be addressed first and
      found empty afterwards; a youth-keyed subscription answers `no-subscriptions` instead, which is
      a different reason and a different fault to diagnose. `NowSubscriptionFixtures.forAnyDefendant`
      exists for that, and the four no-op runs are told apart by `completion_reason` alone — which is
      the whole of C33.)*
- [x] T069a [A] [US1] The e2e sufficiency matrix: four further suites in `e2e/`, each driving the
      **real** pipeline over the whole stack — the Service Bus emulator, Postgres, a Redis container
      and one WireMock server standing in for all three HTTP contexts at once (results-query,
      reference-data and progression have disjoint paths, so one server is one fixture rather than
      three). T069 proves the shapes a healthy service produces; these prove the legs it takes when
      something is wrong, which are the legs no single-adapter suite can hold to a settlement:
      - `e2e/PayloadSourceEndToEndIT` — the cache answers and the query side is never asked; the
        cache misses and the query side answers, and the run still completes; **both** miss ⇒
        TRANSIENT, the delivery handed back, and a redelivery that finds the payload completes it
        (C32: the legacy stops silently on the pair).
      - `e2e/SubmissionOutcomeEndToEndIT` — an address-less youth ⇒ FAILED **before any POST**, with
        WireMock proving zero requests reached progression (C29 with C1); a 400 ⇒ FAILED, parked, and
        `processed_output.status = FAILED` carrying `response_code`; a 5xx then a 202 ⇒ one POSTED
        row and no second register (C3); a 200 that is not 202 ⇒ `SUBMISSION_NOT_ACCEPTED`; a refusal
        every delivery ⇒ parked with `exhausted_message_id`; the same hearing re-shared under a new
        `sharedTime` ⇒ two requests, two POSTs, two POSTED rows.
      - `e2e/RegisterAddressingEndToEndIT` — reference data unanswered ⇒ TRANSIENT and a redelivery
        that succeeds; reference data answering an empty set ⇒ COMPLETED `no-subscriptions` (the CS1
        split, both halves through the running service); an adult-first, youth-second hearing ⇒ the
        youth's register is produced and posted through the whole stack (C31).
      - `e2e/RunDeadlineEndToEndIT` — a response that arrives more slowly than the run's remaining
        budget ⇒ `PROCESSING_DEADLINE_EXCEEDED`, **abandoned** rather than parked, and a clean
        redelivery that completes. The suite records what the shape of that injection has to be:
        `PropertiesValidator` budgets every step's worst case plus a fixed margin against the
        deadline, so no delay a *timeout* would cut short can reach it — only a response that
        arrives slowly rather than late.
      Names are in plan.md's test matrix.
      *(done — 13 further cases across the four suites, all green on introduction and recorded as the
      observed result. Four things this task settled.
      **(1) The C32 pair is reported twice, by design, and the suite now says so.** The first draft
      asserted one ERROR for a cache miss beside a query miss and found two: the payload adapter
      reports that neither source held the hearing — the state C32's legacy has no word for — and the
      pipeline reports what it decided that is worth. They are different facts from different layers,
      both bounded, both correlated, and the assertion names each rather than counting them.
      **(2) A deadline overrun cannot be injected with a delay.** `PropertiesValidator` budgets every
      step's connect and read timeouts plus every wait its retry policy can take plus a fixed 30s
      margin, and refuses to start unless that total is strictly shorter than the processing
      deadline — so on any startable configuration a delay long enough to reach the deadline is one
      the read timeout cuts short first, which is a payload or reference-data failure and a different
      scenario. What overruns a run is a response that arrives *slowly*: a body delivered in pieces
      trips no read timeout, since each individual read returns promptly, and only the run's own
      budget notices. `RunDeadlineEndToEndIT` injects exactly that (42 chunks a second apart against
      a two-second read timeout) at the floor the fixed margin leaves — a 40s deadline, every timeout
      in hundreds of milliseconds — and takes 44s, which is the fastest honest version there is.
      **(3) The transient-then-recover cases are made deterministic by the stub, not by the suite.**
      Seeding a cache or fixing a stub partway through races the broker's redelivery, and the broker
      wins in well under a second. Each such case therefore uses a WireMock scenario that fails
      exactly one delivery's worth of attempts and succeeds afterwards — one 404 for the payload pair
      (a 404 is "not held" and is never retried inside a run), two 503s for the reference-data read
      (which retries twice inside one run), one dribble for the deadline.
      **(4) Six submission outcomes, and the one no adapter suite can assert.** A register the frozen
      contract refuses reaches no socket at all — provable only against the single server that would
      have received it — and beside it: a 400 parked with `response_code` recorded, a 500-then-202
      accepted after one retry, a 200 refused as `SUBMISSION_NOT_ACCEPTED`, a permanent outage parked
      after exactly five deliveries and ten POST attempts carrying `exhausted_message_id`, and a
      re-share producing two requests, two POSTs and two POSTED rows — which is the honest guarantee
      this service makes rather than a defect: the duplicate is absorbed by progression's own
      `max(register_time)` sweep.)*

- [x] T070 [A] [US1] `e2e/MessageAccountingIT`, `e2e/TraceabilityIT`, `e2e/FailureSignalIT` —
      the inherited accounting/tracing/failure-signal proofs.
      *(done — 11 cases, all green on introduction. Three things worth stating.
      **(1) The accounting rule is ported whole, and the burst is this repo's addition.** The mixed
      batch proves the five outcomes are reachable and mutually exclusive, and the six pure cases
      beside it prove the exclusivity by handing the rule evidence that contradicts itself — a
      completed request with a copy on the dead-letter queue, a parked one still on the queue it
      arrived on, a claimed run whose message has vanished — each of which must be accounted for by
      *nothing*. What the informant's batch cannot see is concurrency: its messages never overlap. A
      burst of six published at once and consumed two at a time (the `maxConcurrentCalls` this
      service ships) is added, and each request is asserted to have run exactly once and released its
      claim — which is the failure the claim exists to prevent and the one a sequential batch cannot
      provoke.
      **(2) The traces are this service's own lines, not the informant's.** The completed trace reads
      Delivery received → Request recorded and claimed → Hearing payload obtained → Request completed
      → Run finished → Delivery acknowledged, and the failing one adds Pipeline run failed, Run
      failed transiently, Request parked after its final permitted delivery, and Delivery parked on
      the dead-letter queue. Both are read through the MDC rather than by matching text, because the
      MDC is what the encoder ships and therefore what a reviewer can actually search; every line in
      both traces carries `requestId`, `hearingId` and `hearingDay`.
      **(3) `FailureSignalIT` records one honest exception to "exactly one ERROR".** A payload
      neither source holds is reported twice — by the adapter that knows both missed, and by the
      pipeline that decided what that is worth — and the suite says so in its own note rather than
      quietly weakening the rule. What it enforces is one line per failed run from the layer that
      settles: five failed runs, five ERRORs, five transient counts and one exhaustion. The
      contract-validation line is deliberately uncorrelated (there is no readable request id to
      correlate by) and is found by its opening words instead.)*
- [x] T071 [A] [US4] Container smoke re-verified with the finished service
      (`scripts/container-smoke.sh` locally + the CI step); compose stack documented in README.
      *(done — **PASS**, recorded verbatim: `[container-smoke] PASS: readiness reported UP within the
      60s budget`, with `Started Application in 2.219 seconds`. Three things this run settled.
      **(1) The finished service still starts as a packaged artefact.** No JUnit suite can say so:
      the `*IT` suites run inside the build's JVM and would all pass with an unbuildable image. What
      started here is the jar in its container against the committed compose dependencies, with the
      whole pipeline wired — every adapter, the vendored schemas read at startup and
      `PropertiesValidator` holding the configuration to its rules.
      **(2) The broker was still coming up when readiness went UP, which is the designed answer.**
      The application logged `onTransportError ... Connection refused ... hostName=servicebus-emulator`
      while reporting ready, because the `servicebus` indicator is deliberately in neither health
      group (spec FR-011): a broker blip must not roll the pods. The smoke run is the only place that
      ordering is observed rather than asserted.
      **(3) The first attempt failed on this machine, and not for the service's reasons.** Host port
      5432 was already bound by a pre-existing container, so `postgres` could not start and the
      script failed and tore its stack down cleanly — which is itself the behaviour the teardown trap
      is for. The run above used a local-only compose override (`COMPOSE_FILE`, host port 55432)
      supplied from outside the repository: the committed script and compose file are exactly as CI
      runs them, and nothing in the tree was changed to make the smoke pass.
      README's quickstart carried two claims that had stopped being true and one that never was:
      PMD is described as explicit-only and outside `check` (constitution 2.0.3 puts `pmdMain` and
      `pmdTest` in it); the Docker prerequisite named only the emulator and Postgres, where the `*IT`
      suites now also start Redis and WireMock; and `./gradlew bootRun`, offered as a bare command,
      **cannot start** — both adapter modes default to LIVE and startup then demands upstream
      endpoints and a `CJSCPPUID`. All three are corrected, with the three environment variables the
      compose `app` service already sets. `specs/.../quickstart.md` carried the same PMD claim and is
      corrected with them. Nothing else in README was touched: the Status section describes a
      bootstrap that finished several phases ago, and rewriting it is T072's.)*
- [x] T072 [US3] Documentation finalisation: DEFECT-FIXES all 31 fixable rows → FIXED with pinning
      tests verified by grep; TECHNICAL_DESIGN/API_CONTRACTS/SOLUTION_BRIEF/CHANGELOG completed;
      README quickstart + Documentation table final.
      *(done — every pinning test named in the register's 36 rows grep-verified against `src/test`:
      84 of 86 named cases matched verbatim; the two that did not were the register's citations,
      not the suites' — C15 cited `transport_security` where the suite carries
      `TransportSecurity.the_cache_connection_should_verify_the_certificate_when_tls_is_used`, and
      C16 cited a `system_user_id_is_required` case that was landed as
      `SubmissionPolicy.a_live_pipeline_without_a_submission_identity_should_fail_startup` — both
      rows repointed at the real names. C16 was the one residual PLANNED row: investigated and
      moved to FIXED — the fix-by-omission is complete in this repo (secrets-scanner workflow
      gates every push; LIVE startup refuses a missing `system-user-id`), while rotation of the
      keys the legacy repo exposed stays externally tracked. C35's status cell gains the FIXED
      prefix its landed fix (`ed3af79`/`5157ef0`) had earned; the three PENDING legacy rows
      (C18/C28/C34) verified accurate. TECHNICAL_DESIGN, API_CONTRACTS and SOLUTION_BRIEF lose
      their target-design banners and every TODO: the state machine decision tree with both
      `PROCESSING_DEADLINE_EXCEEDED` origins, the shared `RetryPolicy`, the fenced output claim +
      `anomaly_summary`, the composition wiring, the porting map, the full configuration table
      with the renamed back-off keys, and the inbound failure-behaviour table. The cutover section
      cites the migration design doc §6.5 for replay assurance; README deliberately carries no
      pre-cutover gate detail. CHANGELOG's duplicate Added headings merged; README Status rewritten
      to the finished increment and the Documentation table completed. No code changes; no code
      defects found in review.)*

---

## Phase 8: Differential audit (US5)

- [x] T073 [US5] Build the legacy oracle: adapt the informant parity-pack CLI to the three
      court-register activities (`SetCourtRegister`, `CourtRegisterSubscriptions`,
      `OutboundCourtRegister`) with clock + `TZ=Europe/London` pinned and provenance recorded;
      record the corpus from the six base hearings × the transferable operators (shared-time,
      drop-optional-field, null-field, empty-array, duplicate-array-entry, unicode-name,
      reorder-array, subscriptions-shape/cardinality, re-share-duplicate) + the new operators
      (group-proceedings, youth-presence, court-centre completeness, multi-defendant,
      legal-entity, address-less) under `src/test/resources/differential/recorded/`.
      *(Done — 351 cases at `src/test/resources/differential/recorded/`, from the analysis-side pack
      `analysis/results-distribution/CourtRegister/differential-pack/`, which is not in this repo.
      The oracle runs the three real activities behind the orchestrator's own group-proceedings
      guard, crossing every `callActivity` with the serialise-step boundary; clock pinned to
      `2026-08-21T09:15:00.000Z`, `TZ=Europe/London` pinned and skippable so the timezone claim is
      testable. Provenance records legacy HEAD `0d63f3ae`, a clean tree, the package-lock digest,
      Node v24.18.1, the oracle digest and — new for this pack — the vendored contract-bundle
      digest, because `contractStatus` is a statement about those schemas. Sixteen operators; the
      six base payloads copied byte-identical; subscriptions a verbatim subset of the real 274-entry
      capture carrying the one entry that covers `B01LY00`. Two contract axes per case: the OUTPUT
      axis classifies the legacy's document against the vendored
      `contracts/progression/` bundle (IN_CONTRACT 123 / SCHEMA_INVALID 66 / NO_DOCUMENT 162), the
      INPUT axis against the published hearing model (220 / 99 / 32); both say IN_CONTRACT on 70
      cases, which are the full parity obligations. Outcomes: 189 documents, 142 no-document, 11
      swallowed exceptions, 9 orchestrator skips. Checked, not claimed: 351/351 deterministic at a
      fixed clock, 2 clock-dependent cases identified and given alternate-clock goldens, 351/351
      byte-identical under `TZ=Asia/Tokyo` and `TZ=America/Los_Angeles` with the pin skipped, and
      `verify-boundary.js` reports 0 cases where the activity boundary changes the output. The
      corpus reproduces C1, C7, C8, C10, C13, C22, C29 and C31 as evidence for T075, and surfaces
      one uncatalogued in-contract loss — a legal-entity defendant throws in `YouthDefendantMapper`
      and the register vanishes — which needs a C-number with review or the legacy stands.)*
- [x] T074 [US5] Write `support/RegisteredDefectFixes` + `DifferentialAuditTest` (red only in the
      sense that unattributed diffs fail) — every legacy-vs-port difference derives from a
      C-number; `SCHEMA_INVALID` corpus cases asserted as classified failures.
      *(Done — all 351 recorded runs go through the real chain (group-proceedings policy, fragment
      builder, subscription matcher, twelve mappers, frozen-contract validator) with the
      subscriptions pre-fetched, in ~2s and with no container, so it runs in `./gradlew build`
      untagged. The register has two halves: a **derivation** for a component the port re-renders and
      whose required value can be computed from the recording's own — `registerDate` (C10, through
      `Europe/London`'s real offsets, both answers accepted in the repeated autumn hour) and
      `wording` (C24, split at the `####` sentinel, re-joined with a newline, the `undefined` residue
      dropped) — and a **claim** for everything a value-for-value derivation cannot express, each
      carrying a predicate that recognises the signature of one fix. The claims are mutually
      exclusive and the audit asserts it, so a divergence two rows could explain fails as loudly as
      one no row explains. Obligations follow the recorder's own output axis: IN_CONTRACT reproduce
      and claim every difference, SCHEMA_INVALID classify (refuse at the contract with a pointer the
      recorder's validator named, or repair under a row), NO_DOCUMENT end where the row governs — and
      a run the legacy ended by swallowing an exception is never agreement, because reporting success
      on a failure is C2. Three `shared-time__absent` cases are held to a refusal rather than a
      comparison: `moment` reads an absent date as *now*, so the recording is a reading of the corpus
      clock and there is no oracle in it. Attribution, whole corpus: C9 236, C24 263, C23 138, C10
      124, C11 123, C31 74, C29 59, C2 18, C22 8, C7 4, C19 4, C26 4, C36 4, C8 1 — zero unclaimed.
      **Two port defects found and fixed, red/green each.** (1) `JsStrings` trimmed with Java's
      `String.trim()` where the legacy trims with `String.prototype.trim()`; they disagree in both
      directions, so a child's name reached the register padded with the U+00A0 it arrived in
      (`unicode-name__nbsp-padded`) — `jsTrim` is now ECMA-262's own set, and the email trim behind
      C27 gets it too (`41c6ae2`/`805599c`). (2) C11's file name took its day from `Dates.localDate`,
      London's calendar day, so a 23:00Z share was filed under the following day and under a
      different day from the subscription set it was addressed by — which C10's row says must move
      and, until now, did not (`shared-time__bst-2300-utc`); `Dates.registerDay` answers the UTC day
      and is asserted equal to `subscriptionDay` (`7efee83`/`360d29a`). C10 and C11 amended to record
      both. `JsonParity` now reports differences as data as well as prose, because a register
      predicate reading a difference back out of a formatted string would be parsing the
      comparator's own sentences.)*
- [x] T075 [A] [US5] Run the audit, commit the report to
      `specs/001-court-register-port/checklists/differential-audit.md` (zero unexplained
      differences), and reconcile DEFECT-FIXES rows against observed diffs (every content-changing
      fix must actually appear in the diff set — a fix that produces no diff is evidence the
      corpus misses its shape; extend the corpus, not the claim).
      *(Done — report at `checklists/differential-audit.md`. **Forwards the audit was already
      clean**: 351/351 cases, every difference claimed by exactly one C-row, nothing unattributed.
      The work of this task was the **reverse** reconciliation, which was not: eight
      content-changing rows explained nothing at all — C4, C5, C12, C20, C21, C25, C30, C35 — and
      the reason was the corpus, not the fixes. The real 274-entry subscription capture matches
      every court-register entry on `selectedCourtHouses` and nothing else (no `informantCode`, no
      prison flag, no NOWs flag, and `anyMajorCreditor` set on **zero** of 274), all six base
      hearings carry both ethnicity descriptions, and every base carries an ordered date with an
      empty `hearingDays` — the one route through `getHearingDate` that neither reads the clock nor
      throws. So five operators were added to the analysis pack and the corpus re-recorded at
      **381**: `subscription-routing` 18 (C4/C5/C30, with the two major-creditor controls that must
      NOT match on the same empty lists), `hearing-date` 4 (C35, all four legs), `ethnicity-partial`
      3 (C25, with a control), `application-reference` 3 (C20) and `asn-record-without-person` 2
      (C21 — reaching `getASN` needs the person-less record *beside* an intact defendant, because
      C19 throws first otherwise). **All 351 earlier `expected.json` files are byte-identical after
      the rebuild**; only `oracleDigest` moved. C12 needed no recording at all — the recorder had
      captured `…now-subscriptions?on=2020-06-02` for a 23:00Z share all along and the audit was
      simply not looking, so it now compares that day against `Dates.subscriptionDay` and reports it
      like any other difference. Two audit-side changes, both narrowing: the clock-dependent
      exclusion now covers only the `shared-time__absent` cases, because C35's clock legs carry a
      complete payload and one clock-stamped field and excluding them would have hidden the row the
      corpus was extended to reach; and C35's claim covers the shape where the port, having no
      ordered date to fall back to, assembles a document the frozen contract refuses **at
      `/hearingDate`** — which is what that row says leg (a) must do. **Result: 381/381, twenty-one
      rows explaining differences, zero unattributed, in `./gradlew build` untagged in ~2 s.**
      Eighteen of the nineteen content-changing rows now appear. The nineteenth is C20, and the
      extension settled it as a finding rather than a gap: its unguarded application lookup has no
      trigger, because `DefendantContextBaseService.js:149` only ever pushes ids of applications
      that passed `isEligible` and the mapper looks them up in that same array, so `applications` is
      always a subset of the ids present. The three `application-reference` cases move the ids,
      remove the eligibility and add an ineligible application, and all three produce a register and
      swallow nothing — measured, not argued. Rows amended to record what the audit reached: C4, C5,
      C12, C20, C21, C25, C30, C35, and the register's differential preamble rewritten to the as-run
      truth. No port defects found in this run; T074's two remain the audit's catch. Full
      `./gradlew build` green — tests, Checkstyle, PMD, SpotBugs and the coverage gate.)*

---

## Dependencies & execution order

- **Phase order is strict**: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Within a phase, `### Tests first`
  tasks complete (red recorded) before their implementation tasks start.
- **Only test tasks are ever [P].** Implementation tasks touching shared production files execute
  in task-ID order under a single owner: T022 (listener/lifecycle), T039 (pipeline wiring),
  T054 → T055 → T055a → T056 (document assembly), T065–T067 (adapter configs), T068 → T068a (final wiring).
- Fixture task T053 must complete before any mapper test that needs a rebuilt fixture records its
  red run against final fixtures (mapper tests may draft against interim fixtures but the recorded
  red/green narrative uses the final set).
- T072 depends on every C-fix task; T075 depends on T072–T074.
- Reviewer gates: each phase ends with the workflow's review pass (code review + QA + contract
  validation) and an external Codex review before its final commit; findings addressed or waived
  with reasons in the commit body.

## Notes

- A guard test that unexpectedly passes on first run is not claimed as a red run — investigate,
  then record honestly.
- Golden/differential artefacts are never edited to make a test pass; a pinned behaviour changes
  only in a commit that also updates the matching DEFECT-FIXES row.
- The legacy repo is read-only throughout; C28's broken cases exist here only as their repaired
  Java twins (T040).
- Commit cadence: at minimum one commit per test-task batch and one per implementation task group;
  phase-final commit follows the review gates.
