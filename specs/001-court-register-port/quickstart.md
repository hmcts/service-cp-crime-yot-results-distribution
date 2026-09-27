# Quickstart: YOT Results Distribution Service

## Prerequisites

- Java 25 on `PATH` (the Gradle toolchain will otherwise provision one)
- Docker (Testcontainers suites, the compose stack and the smoke script need it)
- Always use the wrapper: `./gradlew`

## Full verification before commit

```bash
./gradlew build            # compiles, all tests (*Test + *IT), PMD, Checkstyle (0 warnings), JaCoCo gate
./gradlew jacocoTestReport # HTML coverage under build/reports/jacoco
```

## One-command end-to-end demo (spec SC-103)

```bash
./gradlew test --tests 'uk.gov.hmcts.cp.yotresultsdistribution.e2e.YotResultsDistributionEndToEndIT'
```

Boots the full context against the Service Bus emulator and Postgres, publishes a request, and
asserts the POST reached the (WireMock) Progression endpoint with a 202, the request completed
`submitted`, and `processed_output.status = POSTED` — plus one case per no-op completion reason.

## Interactive local run

```bash
docker compose up -d postgres servicebus-emulator
./gradlew bootRun
```

The queue `yotresultsdistribution.requests` is declared in `docker/servicebus-emulator/config.json` (the
same file the tests mount, so local, CI and tests share one queue definition). `bootRun` does not
inherit compose environment variables; the defaults in `application.yaml` point at the compose
ports. Payload and reference-data adapters default to `LIVE` — set
`YOTRESULTSDISTRIBUTION_PAYLOAD_MODE=STUB` and `YOTRESULTSDISTRIBUTION_REFERENCEDATA_MODE=STUB` for a
dependency-free run.

### Health

```bash
curl -s localhost:8082/actuator/health/readiness   # db + intakeStartup gate readiness
curl -s localhost:8082/actuator/health             # servicebus reported, never gates readiness
```

### Poking at it

Publish a request to the emulator (see `e2e` support fixtures for a valid body):

```json
{"source":"RESULTS","requestId":"<uuid>","hearingId":"<uuid>",
 "hearingDay":"2026-08-27","sharedTime":"2026-08-27T14:31:02.115Z",
 "eventType":"Hearing_Resulted","userId":"<uuid>"}
```

## Container smoke

```bash
./scripts/container-smoke.sh   # builds the jar + image, starts compose deps, polls readiness (60 s budget)
```
