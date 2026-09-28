# Research: Consolidate the progression court-register leg

Decisions the plan rests on, each with rationale and the alternatives weighed. Source references are
to local clones as at 2026-09-05 (`cpp-context-progression` `main` `79edf7cf3d`;
`cpp-context-system-doc-generator` `be26a916a`; `cpp-context-notification-notify` `992a414a`;
`framework-libraries` `58aad8664`; `cpp-aks-deploy` `main`). The design (Confluence *Court Register
Service*, rev 2.1) is the authority; this file records what the plan derived from it.

## 1. Payload hand-off to systemdocgenerator — direct write to the shared file-service database

- **Decision**: `FileServicePayloadStore` inserts the PDF payload into the framework file service's
  Postgres (`metadata(metadata jsonb, file_id)`, `content(file_id, content bytea, deleted)`) through a
  second, write-only `DataSource`, with a service-minted `file_id` persisted on `register_batch`
  first. Then `POST generate-document {templateIdentifier: OEE_Layout5, conversionFormat: pdf,
  payloadFileServiceId, sourceCorrelationId: batch_id, originatingSource: YotResultsDistributionService}`.
- **Rationale**: `generate-document` requires `payloadFileServiceId` (schema in
  `contracts/systemdocgenerator/`), a file already in the file service, which is **one shared
  database per stack** (`cpp-aks-deploy/ansible/group_vars/common.yaml.j2:26-31`, `DS.fileservice`).
  The inserts are exactly the framework's own (`MetadataJdbcRepository.java:33`,
  `ContentJdbcRepository.java:37`); the metadata keys are progression's
  (`CourtRegisterEventProcessor.java:161-171`).
- **Alternatives**: SDG's multipart `document-payload-upload` — rejected: it re-stores under a new id,
  fires `generate-document` itself with `originatingSource: systemdocgenerator` and no correlation,
  returns 202 with no body (`SystemDocGeneratorCommandApi.java:45-59`,
  `PayloadRetrievalService.java:30-56`). Azure Blob + in-service rendering — rejected by decision 2a
  (renderer stays SDG). An additive SDG change carrying `originatingSource`/`sourceCorrelationId`
  through the upload — recorded as the fallback if the file-service write is refused (design §13 Q20).
- **Risk noted**: the local `framework-libraries` clone is from 2023-12; the vendored DDL (001–006)
  must be checked against the deployed `fileservice` database in STE before PH.02 ends (a task).

## 2. Completion — the `public.event` topic first, the SDG query API as the safety net

- **Decision**: durable JMS subscription to `public.event` with selector
  `CPPNAME IN ('public.systemdocgenerator.events.document-available',
  'public.systemdocgenerator.events.generation-failed')`, filtered on
  `originatingSource == YotResultsDistributionService`, correlated on `sourceCorrelationId` (= `batch_id`)
  with `payloadFileServiceId` as the cross-check. A batch still GENERATING past the grace period is
  reconciled once via `GET systemdocgenerator-query-api/…/document/{payloadFileId}` and otherwise
  fails `GENERATION_TIMED_OUT`. Both paths call the same `DocumentOutcomeSink`.
- **Rationale**: it is how progression does it (`SystemDocGeneratorEventProcessor.java:131-146`),
  the events carry the correlation fields (schemas vendored), and Boot services in this estate
  already run production-grade Artemis configuration (`cp-case-document-knowledge-service`
  `application-artemis-jms.yml`; STE values for it and `cp-court-list-publishing-service`); the
  listener shape is `service-cp-crime-results-enforcementgateway`'s `ListingPublicEventListener`
  (`@JmsListener` with `CPPNAME` selector; `spring.jms.pub-sub-domain`, `subscription-durable`,
  `client-id`; `build.gradle:57-60` javax/jakarta exclusions). This service departs from that shape
  in one respect (2026-09-14): the subscription is shared, with no client id, so that a deployment
  of many replicas can attach to the one subscription instead of the second pod being refused. The distinct `originatingSource`
  keeps progression's still-deployed branch (`COURT_REGISTER.equalsIgnoreCase`) inert for our
  documents, so there is no two-subscriber window.
- **Alternatives**: poll-only — kept as `completion=poll-only`, an explicit escape hatch; rejected as
  default because the platform pattern is event-driven and polling would carry the flow's only
  synchronous coupling to SDG.

## 3. The flag from Boot — `azure-data-appconfiguration` with workload identity, per-run, fail-closed

- **Decision**: `AppConfigurationFlagReader` uses `ConfigurationClient.getConfigurationSetting(
  ".appconfig.featureflag/YotResultsDistributionService", <stack label>)` with `WorkloadIdentityCredential`,
  parses the feature-flag JSON's `enabled`, 2 s timeout, no cache, returns `ON | OFF |
  UNREADABLE(reason)` and never throws. The job reads it once at run start; OFF/UNREADABLE ⇒ run
  skipped and counted. The CLI reads it too (`--ignore-flag` to bypass, echoed in output).
- **Rationale**: the framework's `FeatureControlGuard` is WildFly-bound (`@GlobalValue`,
  `FeatureControlConfiguration.java:19-28`); the legacy JS client is an HMAC REST read with the same
  key and label (`FeatureFlagClient.js:16,79`). The SDK is already BOM-managed. Fail-closed matches
  the producer; "unreadable ⇒ legacy generates" is the only safe default when three readers disagree.
- **Alternatives**: `spring-cloud-azure-feature-management` — rejected: it caches/refreshes by
  design and adds a starter for one boolean; a Helm value — rejected: a second lever (constitution III).
- **Platform ask**: `App Configuration Data Reader` for the `yotresultsdistribution:` identity (design §8).

## 4. Scheduling — `@Scheduled` in `Europe/London` with ShedLock

- **Decision**: `@Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Europe/London")` on
  `RegisterGenerationJob`, `@SchedulerLock(name = "register-generation", lockAtMostFor = run deadline
  + margin)` with `shedlock-provider-jdbc-template` on the service's Postgres (`shedlock` table in
  V2). `yotresultsdistribution.generation.zone` is validated at startup to equal `Europe/London` unless
  `zone-override-acknowledged=true`.
- **Rationale**: the requirement is 18:00 wall-clock in BST and GMT alike. The legacy fires in the
  systemscheduling JVM's default zone because Quartz's trigger is built without `inTimeZone`
  (`CppJobScheduler.java:76`) — an ambiguity the service must not inherit. ShedLock is the
  informant PH.04 shape (IR-DESIGN §7.1) and the `cp-case-document-knowledge-service` precedent.
- **Alternatives**: keep system-scheduling calling a new endpoint — rejected: no REST API, and a
  second cross-context dependency on cutover.
- **Verified in SIT, 2026-09-07** (design §13 Q21): `select trigger_name, cron_expression,
  time_zone_id from qrtz_cron_triggers where trigger_name = 'Daily Court Register'` against
  `systemschedulingviewstore` on `psf-sit-ccm01-supporting` answers `0 0 18 ? * MON-FRI *` with
  `time_zone_id = UTC` (`Daily Informant Register` is the same expression at 19:00 UTC). So the
  ambiguity the rationale above names is settled, and not in the courts' favour: the trigger row
  carries **UTC**, so the legacy court register goes out at **19:00 BST** through the summer and at
  18:00 only in winter. This service's `Europe/London` schedule corrects that rather than
  reproducing it - the requirement is 18:00 wall-clock in BST and GMT alike - which makes it a
  **cutover-comms item**: from the switch, the summer register lands an hour earlier than the courts
  have been receiving it. PRD is to be confirmed with the same query before the switch; SIT is
  evidence about the configuration, not about PRD's copy of it.

## 5. `defendantType` — from the hearing's own court application

- **Decision**: `DefendantTypeResolver` applies `CourtRegisterHandler.getDefendantType`'s rule
  (`:131-153`) to `hearing.courtApplications[]` matched by `courtApplicationId`: default
  `Applicant`; `Appellant` if `type.appealFlag && type.applicantAppellantFlag` and
  `applicant.masterDefendant` present; `Respondent` if any `respondents[].masterDefendant
  .masterDefendantId` is among the document's defendants. Result set on the stored document's
  `defendantType`.
- **Rationale**: the fields exist on the internal hearing (verified on
  `cpp-context-results` IT fixtures `public.hearing-result_reshare_crown_judicialresults_in_
  application_only.json` and the mags twin; flags `required` in core-domain
  `courtApplicationType.json:159-160`). No cross-aggregate read, no contract change, no coupling.
- **Deviation pinned**: progression reads the aggregate's *current* respondents; the service reads
  the hearing's as-at-hearing copy. Identical for type flags; a respondent list edited after the
  hearing could differ. Recorded as a deviation with a pinning test (design §13 Q25).

## 6. The Java oracle — goldens recorded by executing progression's classes

- **Decision**: a scratch JUnit harness in a **local, uncommitted** module of `cpp-context-progression`
  feeds recorded 001 documents (grouped into batches) through `CourtRegisterPdfPayloadGenerator
  .mapPayload` and hearings through `CourtRegisterHandler.getDefendantType`, and writes
  `src/test/resources/goldens/progression/{pdf-payload,defendant-type}/*.json` here with a
  `PROVENANCE.md` (progression commit, `criminal-court-public-model` version, corpus digest).
  `PdfPayloadMapperTest` asserts byte-identical output; `DefendantTypeResolverTest` asserts equality.
- **Rationale**: both are pure functions of JSON; recording from the real classes is cheaper than a
  Node oracle and just as binding; the PDF payload is the `OEE_Layout5` template contract.
- **Alternatives**: hand-written expectations — rejected (would encode our reading, not progression's).

## 7. The register store — widen `processed_output`, not a new table

- **Decision**: V2 adds `document jsonb`, `hearing_id`, `hearing_date`, `court_house`,
  `register_time`, `defendant_type`, `batch_id`, `superseded_at`, `superseded_by`,
  `recorded_flag_state` to `processed_output`; status widens to RECORDED/GENERATED/NOTIFIED/
  SUPERSEDED/FAILED; partial index on active unbatched rows; `register_batch` and
  `register_notification` new; `shedlock` new.
- **Rationale**: 0..1 per command already; keeps `(source, request_id)` FK chain and `request_digest`
  (now of the stored document) for the differential audit; column-for-column replacement of
  `court_register_request` with per-batch facts moved to the batch (design §4.6).
- **Alternatives**: a fresh `register_record` table — rejected (two truths per command).

## 8. Supersession at the write

- **Decision**: `RegisterStore.record` inserts the new row and, in the same transaction, marks any
  earlier RECORDED (unsuperseded, unbatched) row for the same `hearing_id` within the same
  `(court_centre_id, register_date)` `SUPERSEDED` with `superseded_by`. Rows already stamped with a
  `batch_id` are never touched.
- **Rationale**: same outcome as progression's `max(register_time) per hearing_id` read-side sweep
  (`CourtRegisterRequestRepository.java:56-59`) with the cross-date flip (P3) removed and the
  property owned by this service (closes design Q3).

## 9. Recipient union (P4) and the terminal no-recipients state (P1)

- **Decision**: `RecipientSet` = de-duplicated union by `emailAddress1` across the batch's records
  (name from first occurrence). Empty ⇒ `NOTIFIED_NOBODY`, terminal, counted.
- **Rationale**: progression keeps only the first non-empty list (`CourtCentreAggregate.java:71-83`)
  and emits an event nobody subscribes to (`:115-127`). Subscriptions are keyed on the court centre so
  the union usually equals the first set; the deviation only bites where subscriptions differ within
  a court centre. Content-affecting ⇒ sign-off marker.

## 10. Notification idempotency

- **Decision**: one `register_notification` row per (batch, address) with a minted `notification_id`
  persisted before the POST; retries reuse it. NN's `Notification` aggregate is keyed on
  `notificationId`, so a retried command reaches the same aggregate.
- **Rationale**: same "what was attempted is the evidence" discipline as 001's `request_digest`.

## 11. Startup refusals vs runtime skips

- **Decision**: refuse to start on: wrong zone without acknowledgement; blank/malformed
  `cr_standard` template id in LIVE mode (P9); generation enabled without file-service datasource,
  flag config or SDG/NN endpoints; `completion=event` without broker config; STUB modes with a
  namespace. Runtime, never a refusal: flag unreadable (skip + count), broker silence (health
  reported, readiness untouched), SDG/NN transient failures (retry then FAILED with reason).
- **Rationale**: configuration errors should fail a deploy; environmental failures should leave the
  pod up and visible with the legacy generating.

## 12. Recorded-while-off rows

- **Decision**: `RegisterStore.record` stamps `recorded_flag_state` from the most recent flag read
  cached **for this purpose only**, and `inbound/RecordedFlagStateSource` keeps that reading fresh
  **on a clock rather than on the traffic**: `start()` reads once immediately when the pod begins
  consuming and every 30 s thereafter, on its own executor thread and never on a delivery's. The
  interval is derived as half `FlagStateSnapshot.WINDOW` (the 60 s a reading is allowed to speak for)
  rather than written as a number of its own, so it cannot drift past the window it exists to stay
  inside. An arrival that still finds no reading - before the first renewal has returned, or after
  one failed - asks for a single on-demand refresh, and arrivals behind it join that one rather than
  starting another. Recording never waits on either: the command is labelled from what is already
  known, which for an absent reading is `UNKNOWN`, treated as OFF for batching. Rows with state ≠ ON
  are excluded from automatic batching and surfaced by `list-batches --recorded-while-off`.
- **Rationale**: commands still on the queue when the producer stops publishing may belong to
  hearings the resumed legacy also processed; automatic batching would double-send them.
- **Why the clock and not the arrival** (the correction this section originally described the other
  way round): this service takes about 160 commands a day on a stack, one every nine minutes on
  average, so a reading refreshed only when an arrival finds it stale is stale for very nearly every
  arrival there is. Each one is labelled `UNKNOWN` and schedules a read that comes back seconds later
  having labelled nobody, and since rows that are not ON are kept out of automatic batching, almost
  every register would wait for somebody to find it with `list-batches --recorded-while-off`. The
  renewal also has to be total: a throw out of a fixed-rate task cancels every later execution of it,
  so a reader that failed once would stop the renewal for the life of the pod and label every row
  `UNKNOWN` from then on, which is a far larger failure than the read that caused it.

## 13. CLI in the image

- **Decision**: `CliMain` (picocli-free, plain args) dispatched by `docker/startup.sh` when `$1` is a
  known command, running the Spring context with `yotresultsdistribution.cli=true` (no listener, no
  scheduler) against the same configuration. Commands: `generate-register --date D [--court-house
  H] [--batch B] [--ignore-flag] [--recorded-before T]`, `notify-register --batch B`,
  `list-batches --date D | --recorded-while-off`, `supersede-before --shared-before T`, `check-flag`.
- **Rationale**: the design's "actuator only" rule and the `replay-dlq` precedent — runs with the
  pod's identity and network path; support needs no data-plane RBAC.

## 14. Test infrastructure

- **Decision**: WireMock stubs for SDG command/query, NN and the App Configuration `kv` endpoint;
  Testcontainers Postgres seeded from the vendored file-service liquibase changesets for
  `FileServicePayloadStoreIT`; `artemis-jakarta-server` embedded broker for `DocumentEventListenerIT`
  (durable subscription across a listener restart); compose gains `artemis`, `fileservice-postgres`
  and a `wiremock` service with mappings for the three HTTP dependencies so `container-smoke.sh`
  can run with generation enabled.

## 15. What stays exactly as 001

Inbound transport, idempotency guard, the transformation chain up to `OutboundContractValidator`,
the payload and reference-data adapters, health policy for the broker and the store, the retry
policy object, the differential audit's document corpus. The 001 progression adapter is retained
behind `yotresultsdistribution.output=progression-post` and its tests keep running.
