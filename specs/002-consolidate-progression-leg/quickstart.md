# Quickstart: Consolidate the progression court-register leg

## Local dependencies

```bash
docker compose up -d postgres servicebus-emulator artemis fileservice-postgres wiremock sdg-echo
```

- `postgres` - the service's store (Flyway V1 + V2 on first start), on 5432.
- `servicebus-emulator` (+ its SQL Server companion) — `yotresultsdistribution.requests`.
- `artemis` - an Artemis broker (`apache/activemq-artemis`, pinned) with `public.event` created as a
  multicast address, on 61616; console on 8161 (admin/admin).
- `sdg-echo` - the helper that closes the loop: it watches WireMock's request journal and publishes
  `public.systemdocgenerator.events.document-available` onto `public.event` after each
  `generate-document`, carrying back the `sourceCorrelationId` and `payloadFileServiceId` the
  service sent, so the event-driven completion path runs locally without a real SDG. What it puts on
  the topic is a framework **JsonEnvelope** - a top-level `_metadata` object (`id`, `name`,
  `createdAt`, `source`, `stream.id`, `correlation.client`) alongside the payload fields, with
  `_metadata.name` the field the listener reads to know which event it holds - and not the bare
  payload, which would reach the listener as an envelope with no name.
- `fileservice-postgres` - a Postgres on 5433 seeded with `docker/fileservice/init.sql`, which is
  the vendored file-service liquibase DDL
  (`specs/002-consolidate-progression-leg/contracts/fileservice/`, changesets 001–006) as plain DDL.
- `wiremock` - on 8089: mappings for systemdocgenerator (command 202),
  notificationnotify (202) and the App Configuration `kv` endpoint, with the flag ON by default.
  See `docker/wiremock/README.md`.

## Run the service with generation enabled

```bash
YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB YOTRESULTSDISTRIBUTION_REFERENCEDATA_MODE=STUB \
YOT_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=00000000-0000-0000-0000-000000000000 \
YOTRESULTSDISTRIBUTION_GENERATION_ENABLED=true \
FILESERVICE_DATASOURCE_URL=jdbc:postgresql://localhost:5433/fileservice \
FILESERVICE_DATASOURCE_USERNAME=fileservice FILESERVICE_DATASOURCE_PASSWORD=fileservice \
SYSTEMDOCGENERATOR_BASE_URL=http://localhost:8089 NOTIFICATIONNOTIFY_BASE_URL=http://localhost:8089 \
APPCONFIG_ENDPOINT=http://localhost:8089 STACK_LABEL=LOCAL \
YOTRESULTSDISTRIBUTION_FEATURE_CREDENTIAL=local-test \
CR_EMAIL_TEMPLATE_ID=11111111-1111-1111-1111-111111111111 \
ARTEMIS_BROKER_URL=tcp://localhost:61616 ARTEMIS_USER=admin ARTEMIS_PASSWORD=admin \
./gradlew bootRun
```

`YOTRESULTSDISTRIBUTION_FEATURE_CREDENTIAL=local-test` is what lets the **real** flag reader read the
WireMock `kv` stub, and without it a generation-enabled run refuses to start on a laptop:
`workload-identity`, the default, builds the credential from `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`
and `AZURE_FEDERATED_TOKEN_FILE`, which only the AKS webhook projects - and a credential's token is
sent by the SDK over TLS alone, so pointing that one at a plain-HTTP stub would not have worked
either. Everything else about the read stays deployed: the reader, the SDK client, the key, the
label, the budget and the fail-closed parsing. Startup refuses `local-test` wherever the endpoint
names a real `.azconfig.io` store or a Service Bus namespace says the pod is deployed.

Startup refuses if generation is enabled and any of the file-service datasource, the flag
configuration, the SDG/NN endpoints or the template id is missing, or if the zone is not
`Europe/London`.

## Drive the flow end to end

The `app` service in `docker-compose.yml` carries the same settings, so the calls below reach a
container with generation enabled against the stubs above - and the flag endpoint reads the one
lever through the real reader on the `local-test` credential. `app` is deliberately not one of the
local dependencies above, because the host-side `bootRun` block wants 8082 to itself, so this block
brings it up and the `bootRun` above stays stopped.

**This block used to be five `docker compose exec app ./startup.sh <command>` invocations.**
Increment 005 replaced the operations commands with the seven endpoints under `/operations/**` and
deleted the CLI, so the same walkthrough is `curl` now. The full endpoint-by-endpoint version is
`specs/005-operations-rest-api/quickstart.md`; what is kept here is the sequence this increment's
own walkthrough was about. No `CJSCPPUID` is sent, because `docker-compose.yml` switches both estate
filters off for the local loop.

**The local stack records no registers, so these calls run against an empty day.** The compose
`app` sets `YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB`, and the stub payload source fetches nothing: a
message published to `yotresultsdistribution.requests` is processed to completion `no-defendants` and
writes no `processed_output` row, so nothing is ever there to batch. `LIVE` is the only source
that yields a register, and it needs the results payload cache, reference data and a CJSCPPUID
identity, none of which this stack has. So the RECORDED to NOTIFIED sequence is proved by
`e2e/RecordEndToEndIT` and `e2e/GenerationEndToEndIT` under `./gradlew test`, not here. What this
block does verify is the other half, which no JUnit suite reaches: that the packaged image serves
the seven paths at all, over the store's own statements and the real flag reader, and answers in
the documented statuses.

```bash
# 1. the service in its own container. Wait for ready before any call below: it is the application
#    that runs the Flyway migration, and the same application is what serves these paths - so a
#    pod that is not ready has neither migrated the store nor mapped an endpoint.
docker compose up -d app
curl -s localhost:8082/actuator/health/readiness      # {"status":"UP"}

# 2. run the generation now instead of waiting for 18:00 London (flag is ON in the WireMock stub).
#    It answers 202 and works in the background, under the SAME ShedLock the 18:00 run takes - so
#    unlike the command this replaced, a call that lands inside the night's own run is refused by
#    the lock rather than racing it.
curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8082/operations/batches/generate -d "{\"date\":\"$(date +%F)\"}"
#    202  {"runId":"…","date":"<D>","overridden":false}

# 3. list the day's batches. An empty day answers 200 with an empty list
curl -s "localhost:8082/operations/batches?date=$(date +%F)"

# 4. flip the flag off and show the gate
curl -X PUT http://localhost:8089/flag/off
curl -s -X POST -H 'Content-Type: application/json' \
  localhost:8082/operations/batches/generate -d "{\"date\":\"$(date +%F)\"}"
#    409  {"status":409,"reason":"flag-off"}            <- refused, and nothing changed
curl -s localhost:8082/operations/flag                  # {"flag":"OFF"}
#    the break-glass is one batch at a time and needs a batch id (400 OVERRIDE_REQUIRES_BATCH
#    without one), which an empty day has none of - see 005's quickstart for the shape

# 5. flag back on (either form; they set the same WireMock scenario state)
curl -X PUT http://localhost:8089/flag/on
curl -X PUT http://localhost:8089/__admin/scenarios/YotResultsDistributionServiceFlag/state -d '{"state":"Started"}'
curl -s http://localhost:8089/__admin/scenarios      # which state the flag is in now
```

The shorthand is `/flag/off` and `/flag/on`, NOT `/__admin/flag/off` as the plan first sketched:
WireMock reserves the whole `/__admin` prefix for its own admin API and never consults the stub
mappings there, so a mapping registered under it is unreachable (it answers 404 from the admin
router - verified against `wiremock/wiremock:3.13.2`). The second form above is WireMock's real
scenario admin endpoint, which is what the shorthand drives.

## Tests

```bash
./gradlew test --tests '*RegisterStoreIT' '*PdfPayloadMapperTest' '*DefendantTypeResolverTest'
./gradlew test --tests '*FileServicePayloadStoreIT' '*DocumentEventListenerIT'
./gradlew test --tests '*GenerationEndToEndIT' '*FlagGateEndToEndIT' '*GenerationFailureEndToEndIT'
./gradlew build            # everything, including PMD/Checkstyle/JaCoCo gates
./scripts/container-smoke.sh   # image ready < 60 s with generation enabled against the compose
                               # stubs, then GET /operations/flag answers flag=ON through the
                               # real reader
```

## Recording the progression goldens (one-off, outside this repo)

See research §6. In a local, uncommitted module of `cpp-context-progression` at `79edf7cf3d`, run
the recording harness over the 001 recorded documents; copy the output to
`src/test/resources/goldens/progression/` with its `PROVENANCE.md`. `PdfPayloadMapperTest` and
`DefendantTypeResolverTest` fail until the goldens are present.
