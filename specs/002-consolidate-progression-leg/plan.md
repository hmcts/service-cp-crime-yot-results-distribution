# Implementation Plan: Consolidate the progression court-register leg

**Branch**: `002-consolidate-progression-leg` | **Date**: 2026-09-05 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-consolidate-progression-leg/spec.md`

## Summary

Move progression's court-register leg into this service. The per-command pipeline keeps everything
increment 001 built up to and including contract validation, then **records** the document in the
service's own register store (a widened `processed_output`) with write-time supersession, instead of
POSTing it. A new `batch/` slice runs at **18:00 Europe/London Mon–Fri** under ShedLock, reads the
`YotResultsDistributionService` flag first (fail-closed), groups active records by (court centre, register
date), maps each batch to the PDF payload progression's `CourtRegisterPdfPayloadGenerator` produces
(ported Java→Java, bug-for-bug), inserts that payload into the shared framework file-service database,
POSTs `systemdocgenerator.generate-document` with the service's own `originatingSource`, and awaits
`document-available` / `generation-failed` on the Artemis `public.event` topic (durable subscription,
`CPPNAME` selector; SDG query API as the grace-period reconciler). On generation it sends one
`notificationnotify.send-email-notification` per distinct recipient with the PDF attached by
`fileId`, recording each outcome. An operations CLI in the image replaces progression's by-date
generation and query endpoints. The progression-leg defects P1–P9 are appended to the defect-fix
register. The 001 POST path stays behind `yotresultsdistribution.output=progression-post` for the documented
fallback sequencing.

Design authority: Confluence *Court Register Service* (CRA) — rev 2 / 2.1 §4.4.1, §4.5, §4.5.1,
§4.6, §5.5, §7.3, §9, §10. Working copy:
`analysis/results-distribution/CourtRegister/service-cp-crime-court-register-design.md`.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (Gradle wrapper; unchanged from 001).

**Primary Dependencies** (additions to the 001 set): `org.springframework.boot:spring-boot-starter-artemis`
(with the `javax.jms` / `artemis-jms-client` exclusions the enforcement-gateway build carries for
Boot 4's `jakarta.jms`); `com.azure:azure-data-appconfiguration` (BOM-managed, already under
`azure-sdk-bom 1.3.8`); `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template`;
`org.apache.activemq:artemis-jakarta-server` **test-only** for the embedded-broker listener IT.
Everything else (JdbcClient, RestClient, Lettuce, Flyway, Micrometer, WireMock, Testcontainers) is
already present.

**Storage**: PostgreSQL 16, Flyway `V2__register_store.sql` (see data-model.md) — widens
`processed_output`, adds `register_batch`, `register_notification`, `shedlock`. **Plus a second,
write-only `DataSource`** to the shared framework `fileservice` Postgres (tables `metadata`, `content`,
DDL pinned in `contracts/fileservice/`), used only by `FileServicePayloadStore`.

**Testing**: as 001 (JUnit Jupiter 6, Mockito, AssertJ; `*Test` no Docker, `*IT` Docker) plus:
WireMock for SDG (command + query) and NN; Testcontainers Postgres seeded with the vendored
file-service liquibase DDL for `FileServicePayloadStoreIT`; embedded Artemis (`artemis-jakarta-server`)
for `DocumentEventListenerIT`; WireMock for the App Configuration REST surface in `AppConfigurationFlagReaderTest`;
goldens for `PdfPayloadMapper` and `DefendantTypeResolver` **recorded from progression's classes**
(research §6) under `src/test/resources/goldens/progression/`.

**Target Platform**: AKS, port 4550 (local 8082); single replica; compose gains `artemis` and a
`fileservice` Postgres for local runs and the container smoke.

**Project Type**: single Spring Boot service, actuator-only HTTP surface, CLI in the image.

**Performance Goals**: ~160 commands/day/stack; a nightly run of tens of batches, sequential, with a
60-minute run deadline for *requesting*; completion is event-driven so a slow render never blocks the
next request.

**Constraints**: constitution v3.0.1 — zone `Europe/London` validated at startup; flag read per run,
no cache, fail-closed; ids minted and persisted before every downstream call; `202` and nothing else;
broker never gates readiness; file-service datasource gates readiness only during a run; no PII at
INFO+; no AI attribution; never two committing agents at once; TDD red-run convention.

**Scale/Scope**: seven user stories; out of scope per spec: cutover, progression's retirement PR,
NN delivery events, PCR, history migration, C18.

### Configuration (this increment)

| Property | Local default | Purpose |
|---|---|---|
| `yotresultsdistribution.output` | `record` | `record` (default) or `progression-post` (001 behaviour, fallback sequencing only) |
| `yotresultsdistribution.generation.enabled` | `false` locally, `true` deployed | Master switch for the job, listener, second datasource and their validators |
| `yotresultsdistribution.generation.cron` / `.zone` | `0 0 18 * * MON-FRI` / `Europe/London` | Zone MUST be `Europe/London` unless `yotresultsdistribution.generation.zone-override-acknowledged=true` |
| `yotresultsdistribution.generation.run-deadline` / `.grace-period` | `60m` / `10m` | Requesting deadline; time a batch may stay GENERATING before the reconciler asks SDG |
| `yotresultsdistribution.generation.lock-at-most-for` | `70m` | How long the job's ShedLock lock is held. The job's `@SchedulerLock` reads this key as a placeholder rather than a literal, so the one configurable duration is written once; startup refuses any value below `run-deadline` plus the fixed `PropertiesValidator.SCHEDULER_LOCK_MARGIN` (10m), because a lock that expires inside a run is the replica that generates the same night twice |
| `yotresultsdistribution.generation.completion` | `event` | `event` (default) or `poll-only` (escape hatch, logged loudly) |
| `yotresultsdistribution.notification.claim-lease` | `15m` | How long the notifying leg's claim on a batch stays live, measured from its **last renewal**. The notifying leg's own setting and deliberately not `generation.grace-period`: how long a batch may hold a document before the reconciler looks is no bound at all on telling its recipients, whose cost is the number of Youth Offending Teams the batch is addressed to times whatever notificationnotify makes of each of them. `RegisterBatchRepository.renewNotificationClaim(batchId, token)` is asked before **every** POST - the retries of one recipient included - before each row's settlement and before the batch's own, so the lease covers **one recipient's retry cycle**; startup refuses any value below `PropertiesValidator.NOTIFICATION_LEASE_MARGIN` (2) `x (max-attempts x (connect-timeout + read-timeout) + (max-attempts - 1) x max-backoff)` - the longest a single POST cycle can take, 98s at the shipped transport - because the way this breaks is a deployment lengthening a timeout and leaving the lease where it was, after which a second notifier takes the batch over and a court centre's register goes out twice. The connect timeout is charged because an attempt that hangs on the connect and then on the read is the longest single thing the leg does, and the waits are the gaps *between* attempts |
| `yotresultsdistribution.feature.endpoint` / `.key` / `.label` / `.timeout` | `${APPCONFIG_ENDPOINT:}` / `.appconfig.featureflag/YotResultsDistributionService` / `${STACK_LABEL:}` / `2s` | The third reader of the flag; required when generation is enabled |
| `yotresultsdistribution.fileservice.url` / `.username` / `.password` | `${FILESERVICE_DATASOURCE_URL:}` … | Write-only datasource; required when generation is enabled |
| `yotresultsdistribution.endpoints.systemdocgenerator` / `.notificationnotify` | `${SYSTEMDOCGENERATOR_BASE_URL:}` / `${NOTIFICATIONNOTIFY_BASE_URL:}` | Internal ingress hosts; command/query API paths appended by the clients |
| `yotresultsdistribution.endpoints.system-user-id` | `${YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID:}` | The `CJSCPPUID` both downstream calls are made under. One identity, because a run asks systemdocgenerator to render a batch and notificationnotify to e-mail it as one caller; the same service identity the 001 results and reference-data reads use |
| `yotresultsdistribution.endpoints.max-attempts` / `.initial-backoff` / `.max-backoff` / `.connect-timeout` / `.read-timeout` | `3` / `1s` / `2s` / `5s` / `10s` | The transport the SDG and NN clients share, at the estate's own defaults, so an endpoint is the only thing a deployment must state. The same five keys the 001 clients carry and the same `RetryPolicy` object behind them, so the taxonomy is one taxonomy (`retry_taxonomy_matches_the_submission_client`); both timeouts are set deliberately, a render request with no read timeout outliving the run deadline meant to bound it |
| `yotresultsdistribution.email.templates.cr_standard` | `${CR_EMAIL_TEMPLATE_ID:}` | UUID, required in LIVE mode when generation is enabled |
| `spring.artemis.broker-url` / `.user` / `.password`; `spring.jms.pub-sub-domain=true`, `.subscription-durable=true` (shared, and no client id — set in `PublicEventsConfig`, which Spring binds no property for) | `${ARTEMIS_*}` | The `public.event` subscription |
| `yotresultsdistribution.publicevents.topic` / `.subscription` / `.selector` | `public.event` / `yotresultsdistribution-service.sdg` / `CPPNAME IN (…)` | Listener destination and filter |
| `yotresultsdistribution.generation.sdg-mode` / `nn-mode` / `fileservice-mode` / `flag-mode` | `LIVE` | `STUB` refused wherever a namespace or generation is in use (same rule as 001) |

## Constitution Check

*GATE: evaluated against constitution v3.0.1 (2026-09-05; re-checked after the PATCH amendment - the commit-type list change affects no gate).*

| # | Principle | Verdict for this increment |
|---|---|---|
| I | Defect-Fix-First with Characterised Legacy | **PASS.** Oracle widens to progression's classes (goldens recorded by executing them, research §6). P1–P9 appended: P1/P2/P3/P4/P5/P9 FIXED with pinning tests named below; P6/P7 RETIRED; P8 MOOT. **P10 appended under review (2026-09-06)**, the defendant-type shapes progression's `getDefendantType` throws on, FIXED with the pinning test named below. P3/P4/P10 carry the sign-off marker (content-affecting). No uncatalogued behaviour change: everything not in a P row or a C row is bug-for-bug (notably C24's `####` in `PdfPayloadMapper`). |
| II | Test-Driven Development | **PASS** — the test matrix names the test for every FR/SC and every P-fix; tasks order tests first; red runs quoted. |
| III | Message-Contract First | **PASS** — inbound unchanged; register document unchanged and enforced at the write against `courtRegisterDocumentRequest.json` (a second `OutboundContractValidator` instance over the frozen register-document schema, behind the core's `RegisterDocumentValidator` port, asked unconditionally before `RegisterStore.record`; the `add-court-register` command stays the transformation's check and the `progression-post` body's, and the submission adapter projects the register back to it); consumed contracts vendored under `contracts/` with provenance and asserted by adapter tests; the flag is the one lever (no second switch is introduced — `yotresultsdistribution.output` is a build-time fallback, not a live switch, and the plan says so in Complexity Tracking); actuator-only HTTP, CLI for operations. |
| IV | Canonical JSON In, Typed Models Out | **PASS** — the stored document is the typed `CourtRegisterDocument` serialised once; `PdfPayloadMapper` consumes it as a Jackson tree (progression's generator was `javax.json` over the raw document, so tree-in/tree-out is the faithful port); SDG/NN bodies are typed records. |
| V | SOLID with Ports and Adapters | **PASS** — new ports `RegisterStore`, `PayloadFileStore`, `DocumentRenderer`, `RegisterNotifier`, `FeatureFlagReader`, `DocumentOutcomeSink`; `batch/` and `pipeline/` classes are pure or depend on ports only; adapters own JDBC/HTTP/JMS/SDK types. |
| VI | Explicit Failure | **PASS** — every batch and recipient transition is a row with a bounded reason; skipped runs are counted; reconciler completions are counted; nothing is inferred from a downstream system. |
| VII | Privacy in Telemetry | **PASS** — batch/recipient logs carry ids and reason codes only; recipient e-mail addresses and names never at INFO+; `TelemetryPrivacyTest` extended. |
| VIII | Estate Conventions | **PASS** — same build/gates/CI; Conventional Commits on the feature branch; no AI attribution; Artemis and App Configuration patterns copied from estate precedents (research §3, §4). |

## Project Structure

### Documentation (this feature)

```text
specs/002-consolidate-progression-leg/
├── spec.md
├── plan.md              # This file
├── research.md          # Decisions with rationale and alternatives
├── data-model.md        # V2 schema, state machines, entities
├── quickstart.md        # Local run, compose additions, CLI
├── contracts/
│   ├── README.md                    # Provenance of every vendored artefact
│   ├── systemdocgenerator/          # generate-document, query.document, document-available, generation-failed
│   ├── notificationnotify/          # send-email-notification
│   ├── fileservice/                 # liquibase changelog + changesets 001–006
│   └── appconfiguration/            # feature-flag value shape
├── checklists/requirements.md
└── tasks.md             # Phase-ordered TDD task list (/speckit-tasks)
```

### Source Code (repository root) — additions and changes

```text
src/main/java/uk/gov/hmcts/cp/yotresultsdistribution/
├── application/
│   ├── DistributionPipeline.java          # CHANGED: RegisterStore.record(...) replaces submit(...); reason `recorded`
│   ├── RegisterStore.java                 # NEW port (record, supersede, mark*)
│   ├── RegisterGenerationService.java     # NEW: one batch — payload → store → request → (await) → notify
│   ├── PayloadFileStore.java              # NEW port
│   ├── DocumentRenderer.java              # NEW port (request, query)
│   ├── RegisterNotifier.java              # NEW port
│   ├── FeatureFlagReader.java             # NEW port
│   └── DocumentOutcomeSink.java           # NEW port (event → batch outcome)
├── domain/
│   ├── CompletionReason.java              # CHANGED: `recorded` replaces `submitted`
│   ├── RegisterBatch.java, BatchStatus.java, BatchFailureReason.java,
│   ├── RegisterNotification.java, NotificationStatus.java, RecordedFlagState.java,
│   ├── FlagDecision.java, GenerationFailedException.java, NotificationFailedException.java,
│   └── PayloadStoreUnavailableException.java
├── pipeline/
│   ├── DefendantTypeResolver.java         # NEW: CourtRegisterHandler.getDefendantType, over the hearing's court application
│   ├── PdfPayloadMapper.java              # NEW: CourtRegisterPdfPayloadGenerator, bug-for-bug
│   └── RegisterTransformationChain.java   # CHANGED: resolves defendantType into the document
├── batch/
│   ├── RegisterGenerationJob.java         # @Scheduled(cron, zone="Europe/London") + @SchedulerLock; flag gate first
│   ├── FeatureFlagGate.java
│   ├── BatchAssembler.java                # group + stamp batch_id; excludes recorded_flag_state = OFF
│   ├── RecipientSet.java                  # union by emailAddress1
│   ├── GenerationReconciler.java          # grace-period GET document/{id}
│   ├── RunReport.java
│   └── cli/ GenerateRegisterCli.java, NotifyRegisterCli.java, ListBatchesCli.java,
│            SupersedeBeforeCli.java, CheckFlagCli.java, CliMain.java
├── adapter/
│   ├── fileservice/  FileServicePayloadStore.java (JdbcClient over the second DataSource)
│   ├── systemdocgenerator/ SystemDocGeneratorClient.java (POST generate-document; GET document/{id})
│   ├── publicevents/ DocumentEventListener.java (@JmsListener), PublicEventEnvelope.java
│   ├── notificationnotify/ NotificationNotifyClient.java
│   ├── appconfig/ AppConfigurationFlagReader.java (ConfigurationClient + WorkloadIdentityCredential)
│   ├── progression/ (kept, wired only when yotresultsdistribution.output=progression-post)
│   └── stub/ Stub{PayloadFileStore,DocumentRenderer,RegisterNotifier,FeatureFlagReader}.java
├── persistence/
│   ├── ProcessedOutputRepository.java     # CHANGED: document, batch_id, supersession, flag state, mark*
│   ├── RegisterBatchRepository.java       # + the notification claim: claimForNotification answers
│   │                                      #   NotificationClaim (CLAIMED | ALREADY_CLAIMED
│   │                                      #   | ABSENT), plus token-fenced renewNotificationClaim
│   │                                      #   and releaseNotificationClaim
│   ├── RegisterNotificationRepository.java # update answers NotificationSettlement (APPLIED
│   │                                      #   | ATTEMPTS_ONLY | ABSENT); the tally is unconditional
│   ├── NotificationClaim.java, NotificationSettlement.java   # the two statements' own answers,
│   │                                      #   bounded: a row count could not carry either
└── config/
    ├── GenerationProperties.java, FeatureFlagProperties.java, FileServiceDataSourceConfig.java,
    ├── SchedulingConfig.java (ShedLock), PublicEventsConfig.java (JMS listener container),
    ├── PublicEventsHealthIndicator.java, FileServiceRunHealthIndicator.java,
    ├── GenerationMetrics.java, Live/Stub generation configs, PropertiesValidator.java (CHANGED: zone, flag, datasource, completion rules)
src/main/resources/db/migration/V2__register_store.sql
src/main/resources/application.yaml            # keys above
docker/startup.sh                              # CLI dispatch: `startup.sh generate-register …` → CliMain
docker-compose.yml                             # + artemis, fileservice-postgres, wiremock (sdg, nn, appconfig)
```

### Port contracts (this increment)

```java
public interface RegisterStore {
    RecordedCompletion recordAndComplete(DistributionCommand command,           // insert + supersede +
                         CourtRegisterDocument document,                        // complete the command,
                         String courtCentreOuCode,                              // one transaction; the
                         String defendantType, RecordedFlagState flagState,     // batch's copy of the
                         Supplier<GuardDecision> completion);                   // OU code
    List<RegisterRecord> activeUnbatched();                                     // RECORDED, unsuperseded, ON
    List<RegisterRecord> batched(UUID batchId);                                 // the batch's own rows,
                                                                                // read back by identity
    List<RegisterBatch> batchesFor(Collection<CourtCentreDay> keys);            // the keys' history, which
                                                                                // is what decides the
                                                                                // supplementary rule (Q27)
    RegisterBatch assemble(RegisterBatch batch, List<RegisterRecord> records);  // writes the batch the
                                                                                // assembler decided and
                                                                                // stamps batch_id
    void markPayloadMinted(UUID batchId, UUID payloadFileId);                   // before the file-service
                                                                                // write; batch stays PENDING
    void markRequested(UUID batchId, UUID payloadFileId);
    void markGenerated(UUID batchId, UUID documentFileId, Instant generatedAt,
                       CompletedBy completedBy);                                // this batch's rows only (P3);
                                                                                // completed_by in the same statement
    void markFailed(UUID batchId, BatchFailureReason reason, String sdgReason,
                    CompletedBy completedBy);                                   // null unless somebody answered
    void markNotified(UUID batchId, NotificationSummary summary);
}

public interface PayloadFileStore {
    void store(UUID fileId, JsonNode payload, PayloadMetadata metadata) throws PayloadStoreUnavailableException;
}

public interface DocumentRenderer {
    void requestRender(RenderRequest request, CallerIdentity caller);          // 202 or throws (classified)
    Optional<DocumentStatus> query(UUID payloadFileId, CallerIdentity caller); // reconciler only
}

public interface DocumentOutcomeSink {                                          // driven by the listener AND the reconciler
    void documentAvailable(UUID correlationId, UUID payloadFileId, UUID documentFileId, Instant generatedAt,
                           CompletedBy completedBy);                            // caller-supplied: EVENT from the
                                                                                // listener, RECONCILER from the
                                                                                // grace-period reconciler
    void generationFailed(UUID correlationId, UUID payloadFileId, String reason, Instant failedAt,
                          CompletedBy completedBy);                             // caller-supplied: EVENT from the
                                                                                // listener, RECONCILER from the
                                                                                // grace-period reconciler
}

public interface RegisterNotifier {
    NotificationOutcome send(RegisterNotification notification, UUID documentFileId, CallerIdentity caller);
}

public interface FeatureFlagReader {
    FlagDecision read();   // ON | OFF | UNREADABLE(reason) — never throws
}
```

### Test matrix

Layers: **U** unit · **W** WireMock · **PG** Postgres `*IT` · **FS** file-service Postgres `*IT` ·
**AR** embedded Artemis `*IT` · **E2E** full context · **CS** container smoke. **Task authors and
DEFECT-FIXES rows use these names verbatim.**

| Area | Planned test | Layer | Covers |
|---|---|---|---|
| Schema | `SchemaMigrationV2IT` | PG | V2 columns, constraints, partial indexes, `shedlock` |
| Recording | `RegisterStoreIT` | PG | FR-001/003: record, supersede same key in one txn, no touch to GENERATED rows, flag-state stamp, `activeUnbatched` excludes OFF/superseded/batched; `markGenerated(batchId)` flips this batch's rows only (P3: `generation_flips_only_the_batchs_own_rows`) |
| Recording | `DistributionPipelineTest` (extended) | U | reason `recorded`; SCHEMA_INVALID before record; store-down ⇒ abandon+suspend; `progression-post` mode still submits |
| Defendant type | `DefendantTypeResolverTest` | U | FR-002 goldens from `CourtRegisterHandler.getDefendantType` (Applicant / Appellant / Respondent / no application); the as-at-hearing deviation pinned |
| Defendant type | `P10`: `DefendantTypeResolverTest.the_shapes_progression_throws_on_are_answered_applicant` | U | P10 pinning: the two goldens that recorded `NullPointerException` (`synthetic__master-defendant-without-flags`, `synthetic__respondents-absent`) are answered `Applicant` and the register is recorded |
| PDF payload | `PdfPayloadMapperTest` | U | FR-007: byte-identical to goldens recorded from `CourtRegisterPdfPayloadGenerator.mapPayload` for every recorded batch shape; C24 `####` → newline pinned (`sentinel_is_substituted_exactly_as_progression_did`) |
| Batch assembly | `BatchAssemblerTest` | U | grouping by (court centre, register date); first row's fileName; recorded-while-off excluded (FR-015) |
| Recipients | `RecipientSetTest` | U | union by `emailAddress1`, name from first occurrence (P4: `recipients_are_the_union_across_the_batch_not_the_first_rows`) |
| Flag | `AppConfigurationFlagReaderTest` | W | FR-006: enabled true/false, 404, 403, timeout, malformed ⇒ ON/OFF/UNREADABLE; label passed; never throws |
| Flag | `FeatureFlagGateTest` | U | OFF/UNREADABLE ⇒ skipped with reason + metric; ON ⇒ proceed; CLI `--ignore-flag` |
| Job | `RegisterGenerationJobTest` | U | FR-005/006: reads flag first; sequential batches; run deadline; run report, including the keys the assembler deferred, in the report, the run's own line and `yotresultsdistribution_deferred_keys`; ShedLock annotation + zone (`job_is_scheduled_in_europe_london`) |
| Job | `SchedulingConfigTest` | U | `Europe/London` validated; override needs acknowledgement (startup refusal otherwise) |
| Generation | `RegisterGenerationServiceTest` | U | payload id minted+persisted before store; store failure ⇒ FAILED PAYLOAD_STORE_UNAVAILABLE (rows stay RECORDED); request 202 ⇒ GENERATING; non-202 2xx ⇒ RENDER_REQUEST_REJECTED; transient ⇒ retry within deadline |
| File service | `FileServicePayloadStoreIT` | FS | inserts match framework DDL (vendored 001–006): `metadata` JSONB + `content` bytea, `deleted=false`; metadata keys as progression's (`fileName, conversionFormat, templateName, numberOfPages, fileSize`) |
| SDG client | `SystemDocGeneratorClientTest` | W | body verbatim incl. `originatingSource=YotResultsDistributionService`, `sourceCorrelationId=batch_id`; media type; `CJSCPPUID`; 202-only; retry taxonomy shared (`retry_taxonomy_matches_the_submission_client`); query mapping |
| Listener | `DocumentEventListenerTest` | U | envelope parse (`_metadata` + payload), `CPPNAME` selector values, originatingSource filter (ignore others), correlation → sink calls |
| Listener | `DocumentEventListenerIT` | AR | durable subscription on embedded Artemis: event published while down is delivered on reconnect; selector excludes other CPPNAMEs |
| Outcome | `DocumentOutcomeSinkTest` | U | GENERATED delegates to `RegisterStore.markGenerated(batchId)` - the sink scopes the flip to the batch it was given and never widens it (the P3 pin itself lives in `RegisterStoreIT`, above); FAILED records sdg reason (P2: `generation_failed_event_fails_the_batch_with_reason`); duplicate event idempotent; `sourceCorrelationId` is the only lookup and `payloadFileServiceId` only a cross-check on it - an unknown correlation is counted `{reason=unknown-correlation}` and never rescued by a matching payload, and a correlation whose payload disagrees with the batch's own is counted `{reason=payload-mismatch}` and applied nowhere |
| Reconciler | `GenerationReconcilerTest` | U/W | grace period; GET applied through the sink; still pending ⇒ GENERATION_TIMED_OUT; `reconciled` metric |
| Notify | `NotificationNotifyClientTest` | W | `POST /notifications/{notificationId}` with media type `application/vnd.notificationnotify.email+json`; body verbatim (`templateId, sendToAddress, fileId, personalisation.yotsName`) and no `notificationId` in it, the id being the path parameter; 202-only; same id in the path on retry |
| Notify | `RegisterNotifierServiceTest` | U | per-recipient rows minted first, each carrying the `notificationId` that goes in the path of its `POST /notifications/{notificationId}`; ACCEPTED/FAILED; NOTIFIED / PARTIALLY_NOTIFIED / NOTIFIED_NOBODY (P1: `a_batch_with_no_recipients_ends_notified_nobody_not_generated_forever`); resend the rows never ACCEPTED, PENDING included, and `notify` re-entrant over a batch that already holds rows (`RecoveringAnUnsettledRow`); a TRANSIENT refusal retried under the same id inside the shared attempt budget and a NON_TRANSIENT one not (`RetryingWhatMayAnswerDifferently`) |
| Config | `ConfigurationValidationTest` (extended) | U | P9: `blank_email_template_refuses_to_start_in_live_mode`; generation-enabled requires fileservice + flag + endpoints; completion=poll-only logged; STUB refused with namespace |
| Health | `PublicEventsHealthIndicatorTest`, `FileServiceRunHealthIndicatorTest`, `ReadinessPolicyIT` (extended) | U/PG | broker never in readiness; file-service datasource in readiness only during a run |
| Metrics | `GenerationMetricsTest`, `GenerationMetricsContextTest` | U | instrument names and labels as shipped: counters `yotresultsdistribution_batches_total{outcome}`, `yotresultsdistribution_generation_request_total{response_code}`, `yotresultsdistribution_generation_reconciled_total`, `yotresultsdistribution_generation_skipped_total{reason}`, `yotresultsdistribution_notifications_total{status,response_code}`, `yotresultsdistribution_public_events_ignored_total{reason}` (`foreign-source`, `unknown-correlation`, `missing-payload-id`, `payload-mismatch` - four bounded reasons on one counter, so a night of outcomes going nowhere is one sum and four faults); timer `yotresultsdistribution_generation_latency`; gauges `yotresultsdistribution_oldest_recorded_unbatched_age`, `yotresultsdistribution_oldest_generating_age`, `yotresultsdistribution_oldest_pending_age`, `yotresultsdistribution_oldest_generated_age` (the batch that holds a document nobody was told about - the state the other two age gauges cannot see, since one reads GENERATING and the other PENDING), `yotresultsdistribution_pending_after_deadline`, `yotresultsdistribution_deferred_keys`, `yotresultsdistribution_flag_read_ok`. Every label is drawn from a bounded enumeration, and the bean is unconditional so the meters do not come and go with `yotresultsdistribution.generation.enabled` |
| Privacy | `TelemetryPrivacyTest` (extended) | U | recipient address/name never at INFO+ |
| CLI | `GenerateRegisterCliTest`, `NotifyRegisterCliTest`, `ListBatchesCliTest`, `SupersedeBeforeCliTest`, `CheckFlagCliTest` | U | FR-016 behaviours; flag refusal without `--ignore-flag`; output shapes |
| CLI | `CliDispatchIT` | CS | `startup.sh generate-register --help` exits 0 inside the image |
| Assembly | `P5`: `RegisterGenerationServiceTest.assembly_failure_fails_the_batch_and_the_run_continues` | U | P5 pinning |
| E2E | `RecordEndToEndIT` | E2E | command → RECORDED row, no HTTP to progression, reason `recorded`; re-share supersedes |
| E2E | `GenerationEndToEndIT` | E2E | seeded rows → job (flag ON via WireMock) → file-service insert → SDG 202 → embedded-Artemis `document-available` → GENERATED → NN 202 per recipient → NOTIFIED; run report emitted |
| E2E | `FlagGateEndToEndIT` | E2E | flag OFF ⇒ skipped, nothing requested; flag unreadable ⇒ skipped; flag ON ⇒ proceeds |
| E2E | `GenerationFailureEndToEndIT` | E2E | `generation-failed` ⇒ FAILED with reason; no event ⇒ reconciler; NN refusal ⇒ PARTIALLY_NOTIFIED; resend via CLI ⇒ NOTIFIED |
| Differential | `DifferentialAuditTest` (extended) | U | recorded documents unchanged vs 001; `PdfPayloadMapper` goldens |
| Smoke | Container smoke (extended) | CS | image ready < 60 s with generation enabled against compose stubs |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| A second `DataSource` to a database this service does not own (file service) | SDG reads its payload from the framework file service and offers no caller-correlatable upload (research §1) | SDG's `document-payload-upload` hides the id and drops correlation; a Blob store would change the renderer (decision 2a rejected it) |
| `yotresultsdistribution.output=progression-post` retained | The design's fallback sequencing (§9) may need the 001 shape live for one release | Deleting the adapter now would make the fallback a re-implementation; the value is set at deploy time, not flipped live, so it is not a second lever |
| One merge commit on the feature branch whose subject is not a Conventional Commit (`41009d0`, "Merge remote-tracking branch 'origin/main' into 002-consolidate-progression-leg") | A push of this branch's work to `main` was rejected because `main` had moved fourteen commits ahead (Spring Boot 4.1.0 to 4.1.1, ShedLock 6.6.0 to 7.9.0, the Gradle wrapper, CI workflow changes, a PMD classpath fix, the imported ruleset files removed). The branch was already published, so its history is append-only and the divergence had to be resolved on the branch | Rebasing would have rewritten published history, which the preamble forbids outright. Cherry-picking main's fourteen commits would have duplicated every one of them and left the branch's own merge base wrong. Composing a conventional subject for the merge by hand would have described a change nobody wrote, and the estate's own six merges on `main` carry the same generated form. **Approved: design owner, 2026-09-09**, as a recorded deviation rather than by amending the constitution to exempt merges in general |
| Durable JMS subscription (broker dependency) | Event-driven completion is the platform pattern and correlates cleanly; polling alone would re-open the two-subscriber knot via a second mechanism | Poll-only kept as an explicit, loud escape hatch for environments without broker access |
| ShedLock on a single-replica service | Cheap insurance that a scaled deployment cannot run two generations | Relying on `replicas: 1` is a deployment fact, not a code guarantee |
