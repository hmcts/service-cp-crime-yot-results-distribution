# Coding Conventions — MOJ / CPP Standard

## Dependency Injection

- Constructor injection ONLY — NEVER use `@Autowired` on fields
- All injected fields MUST be `private final`
- Use Lombok `@RequiredArgsConstructor` OR an explicit constructor
- The application layer injects **port interfaces**, never adapter classes

```java
// CORRECT
private final HearingPayloadSource hearingPayloadSource;
private final IdempotencyGuard idempotencyGuard;

public DistributionPipeline(HearingPayloadSource hearingPayloadSource,
                            IdempotencyGuard idempotencyGuard) {
    this.hearingPayloadSource = hearingPayloadSource;
    this.idempotencyGuard = idempotencyGuard;
}

// WRONG — never do this
@Autowired
private RedisHearingPayloadAdapter adapter;
```

## DTOs and Data Classes

- Java records for ALL value types — immutable by design
- `DistributionCommand` (inbound message) is a record; parse it from `JsonNode`, validate explicitly
- **JsonNode-canonical inbound, typed outbound**: the hearing payload stays a Jackson tree behind a
  typed facade; only what this service *produces* (the `add-court-register` body) is a typed
  record tree
- `ObjectMapper` configured with `USE_BIG_DECIMAL_FOR_FLOATS`; outbound serialisation
  `@JsonInclude(NON_NULL)`
- Use sealed interfaces for polymorphic types (e.g. pipeline outcomes)

## Error Handling

- Custom exceptions extending `RuntimeException`; classify at the throw site as transient or
  non-transient (e.g. `TransientPipelineException` / `NonTransientPipelineException`)
- **NEVER swallow exceptions.** No empty catch blocks, no catch-and-log-and-continue, no returning a
  success value from a catch block. Catch only to classify and rethrow, or to map to a persisted
  `FAILED` state that is then explicitly dead-lettered
- The message listener is the only place that converts an exception into a settlement decision
- `@ControllerAdvice` and `ProblemDetail` are permitted **for the operations API only** (the `api/`
  package, `/operations/**`), which is the one HTTP request surface there is to map errors onto.
  The advice maps a refusal to 409 (or 400 for an argument that will not read) and a failure to 500,
  each carrying the bounded `reason` the CLI command printed — never exception text, never a
  fragment of a store's or a far end's words, and never a value the caller supplied. Nowhere else:
  the message listeners and the scheduled jobs convert an exception into a settlement or a persisted
  state, not into a response, and an advice reachable from them would be a swallowed exception with
  a status code on it

## Messaging

- Peek-lock, auto-complete disabled; exactly one explicit `complete()` / `abandon()` / `deadLetter()`
  per message on every path
- `maxDeliveryCount` 5; `maxConcurrentCalls` 2 to start
- `messageId` = `"{source}:{requestId}"`; broker duplicate detection on; replay tooling always mints a
  fresh `messageId`
- Persist state **before** settling
- ASB processor health is registered as a liveness/details indicator only — **never** in the
  readiness group

## Persistence

- Flyway migrations at `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`
- Migrations are additive and forward-only; never edit an applied migration
- The processed-log insert is the idempotency claim — a unique-constraint violation means duplicate
  delivery, and is handled, not logged as an error

## Enums and Routing

- Java enums for fixed value sets (`RequestStatus`, `OutputStatus`, `EventType`)
- Switch expressions for routing — the compiler enforces exhaustive coverage
- Include a `fromValue(String)` factory when parsing wire strings; unknown values are an explicit
  non-transient failure, never a silent default

## Logging

- SLF4J with Logback (via the Spring Boot starter)
- Lombok `@Slf4j` or `private static final Logger log = LoggerFactory.getLogger(...)` — the only
  allowed forms
- MDC on every message: `requestId`, `hearingId`, `source` (cleared in a `finally`)
- `LogstashEncoder` emits structured JSON on the `json` Spring profile
- NEVER use `System.out.println`, `System.err.println`, or `Throwable#printStackTrace()`
- NEVER log secrets, tokens, or connection strings
- **NEVER log defendant PII at `info` or above** — no names, addresses, dates of birth, ASNs, URNs.
  Identifiers only

## Imports

- NEVER use wildcard imports (`import java.util.*`) — always explicit imports

## Naming Conventions

| Component        | Pattern            | Example                          |
|------------------|--------------------|----------------------------------|
| Application svc  | `*Pipeline` / `*Service` | `DistributionPipeline`     |
| Port (interface) | capability noun    | `HearingPayloadSource`, `RegisterSubmissionClient` |
| Adapter          | `*Adapter`         | `RedisHearingPayloadAdapter`, `StubRegisterSubmissionAdapter` |
| Message listener | `*MessageListener` | `YotResultsDistributionMessageListener` |
| Operations controller | `*Controller` | `BatchesController`, `FlagController` |
| Request record (API) | `*Request`     | `GenerateRegisterRequest` |
| Response record (API)| `*Response`    | `BatchListingResponse` |
| Repository       | `*Repository`      | `ProcessedRequestRepository`     |
| Entity           | domain noun        | `ProcessedRequest`               |
| Record (in)      | `*Command`         | `DistributionCommand`            |
| Record (out)     | `*Document` / `*Request` | `CourtRegisterDocument` |
| Exception        | `*Exception`       | `TransientPipelineException`     |
| Config           | `*Configuration` / `*Properties` | `ServiceBusConfiguration`, `ServiceBusProperties` |
| Test             | `*Test` / `*IT`    | `DistributionPipelineTest`       |

## Testing Conventions

- JUnit 5 + Mockito + AssertJ for unit tests; `@ExtendWith(MockitoExtension.class)`
- `@Nested` classes with `@DisplayName` for grouped scenarios
- Method naming: `{action}_{scenario}_should_{expectation}`
- The application layer is tested with plain mocks — **no Spring context**
- Testcontainers for integration tests (suffix `*IT`): `servicebus-emulator` for the consumer,
  Postgres for the processed-log
- WireMock for external HTTP stubs (use `dynamicPort()`), asserting exact CPP vendor media types
- **Operations API**: `@WebMvcTest` slice tests per controller, with the application service mocked
  and the identity client stubbed — one case that the caller without "Second Line Support" is
  refused, one that the caller with it is served, and one per refusal the endpoint can answer with.
  A contract test asserts the controllers against `src/main/resources/yot-results-distribution-openapi.yaml`. No test asserts
  a response body that echoes the caller's own characters back. A success record may carry this
  service's own parse of an identifier or instant (FR-025); the case that covers such a field
  sends a non-canonical but parseable spelling and expects the canonical rendering, so what is
  pinned is the parse and not an echo
- **Golden-parity tests**: Jest fixtures copied byte-identical into `src/test/resources/fixtures/`;
  one JUnit twin per Jest case; comparison field-order-insensitive, array-order-sensitive,
  BigDecimal-tolerant; registered deviations asserted explicitly
- TDD: write the failing test first, see it fail for the right reason, then implement
- Logging in tests: SLF4J only
- Test commands: `./gradlew test` runs the whole suite — unit, integration and `*IT` classes alike,
  since there is no separate `integrationTest` task; the Testcontainers suites run under `test` and
  need Docker only when those tests are in the selection. `./gradlew build` = compile + `test` +
  **every analysis**, because all of them are in `check` and none has to be named on a command
  line: Checkstyle (`config/checkstyle/google_checks.xml`, `maxWarnings = 0`) over
  **`checkstyleMain` and `checkstyleTest`, with `checkstyle-suppressions.xml` on the test sources**;
  PMD, pinned to 7.22.0, over **`pmdMain` against `.github/pmd-ruleset.xml` and `pmdTest` against
  `.github/pmd-test-ruleset.xml`**; and the JaCoCo coverage gate
  (`jacocoTestCoverageVerification`, LINE ≥ 0.88 / BRANCH ≥ 0.85, with `Application` and
  `config/**` excluded). Naming a task is a way to run one of them **sooner**, never a way to run
  one at all — there is no `onlyIf` in `gradle/pmd.gradle` and `pmdTest` is not disabled
  (constitution 2.0.3 removed both, and `CLAUDE.md`'s Build & Test table has said so since).
