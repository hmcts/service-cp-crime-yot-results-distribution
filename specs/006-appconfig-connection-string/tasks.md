# Tasks: App Configuration connection string

TDD red-run convention: a test task lands its compile-safe seams so the recorded red run is a
failing assertion, never a compile error; the paired implementation task quotes the green run.

Format: `[ID] [P?] [US#] Description`

## Phase 0: Governance

- [x] **T001** Amend the constitution 5.0.3 → 5.1.0 (Technology Stack: Feature flag,
  Secrets/identity); update `CLAUDE.md`, `README.md`, `docker/wiremock/README.md` and
  `.claude/rules/design_rules.md`; point `.specify/feature.json` here. Run `/speckit-analyze`
  before merge.
  `/speckit-analyze` (2026-09-29, after T004):
  - Zero CRITICAL findings and no constitution conflict.
  - Two MEDIUM:
    - `plan.md`'s Changes and Test matrix did not name the remediation; fixed with T005.
    - SC-002, the Docker-backed `./gradlew build` with the JaCoCo gate, is still open (see T003).
  - One LOW: FR-007 was not mapped in the test matrix; fixed with T005.

## Phase 1: Tests (red)

- [x] **T002** [US1] [US2] [US3] Rewrite `ConfigurationValidationTest` (`PublishedLocalPair`,
  `ConnectionStringPrivacy`, the flag cases of `GenerationDownstreams`, a `ShippedConfiguration`
  case), `GenerationWiringContextTest.FlagCredential`, add `FeatureFlagPropertiesTest`, and move
  the test support to the connection string. Seams: `FeatureFlagProperties.connectionString` and
  `PUBLISHED_LOCAL_ID` land with the old behaviour so the run is red on assertions.
  Red run (2026-09-29, `-Dtest.noFailFast=true`, the four suites): 245 tests completed, 48 failed,
  every one an `AssertionError` - `PublishedLocalPair` 15, `GenerationDownstreams` 16, the
  generating cases elsewhere that now carry only a connection string 12,
  `FeatureFlagPropertiesTest` 2, `ShippedConfiguration` 1, `ConnectionStringPrivacy` 1,
  `FlagCredential` 1.

## Phase 2: Implementation (green)

- [x] **T003** [US1] [US2] [US3] `FeatureFlagProperties`, `LiveFeatureFlagConfig`,
  `AppConfigurationFlagReader`, `PropertiesValidator`, `application.yaml`, `docker-compose.yml`;
  delete the workload-identity flag path. The affected suites, Checkstyle and PMD are green; the
  full `./gradlew build` is **not yet** (SC-002, below).
  Green run (2026-09-29, `-Dtest.noFailFast=true`, the four suites plus `HttpSurfaceTest` and
  `GenerationMetricsContextTest`): 264 tests, 0 failed. Full `./gradlew test` on a host with no
  Docker: 3477 tests, 137 failed, every one a Testcontainers "could not find a valid Docker
  environment" or a context-threshold cascade from one - no non-Docker failure. `checkstyleMain`,
  `checkstyleTest`, `pmdMain` and `pmdTest` clean. **Open:** the JaCoCo gate and the `*IT` suites
  need a Docker-backed `./gradlew build` before merge.

## Phase 3: Gate remediation (round 1: code-reviewer, qa, spec-validator)

- [x] **T004** [US2] [US3] Red tests for the gate findings:
  - `GenerationWiringContextTest.FlagCredential`: an unparseable string makes the reader's
    construction fail under the setting's name, with no cause and no secret, Id or host.
  - `ConfigurationValidationTest`:
    - the shape check applies with generation off;
    - `Secret=not*base64!` is refused;
    - `Endpoint =`, `Id =` and `Secret =` are refused;
    - a padded endpoint with a deployed Id is refused;
    - plain http is refused against a real store or on a deployed pod, and still starts against a stub;
    - a context carrying `LiveFeatureFlagConfig`, with generation on and off, has no part of the
      value anywhere in its cause chain.
  - `FeatureFlagPropertiesTest.Parts`: SDK-identical part reading.
  - The FR-006 assertions now also cover the Id and the host.
  - No seam needed: every case is written against the existing API.
  - Red run (2026-09-29, `-Dtest.noFailFast=true`, the three suites): 232 tests, 16 failed, every
    one an assertion failure (15 `AssertionError`, 1 `AssertionFailedError`). `GenerationDownstreams`
    9, `FlagCredential` 5, `FeatureFlagPropertiesTest$Parts` 2.
- [x] **T005** [US2] [US3] Make them pass:
  - `LiveFeatureFlagConfig` rethrows the builder's `IllegalArgumentException` as a
    setting-named `IllegalStateException` with no cause.
  - `PropertiesValidator` asks the shape of any string that is set, and https of a real store or a
    deployed pod.
  - `FeatureFlagProperties.connectionStringPart` reads parts as the SDK does.
  - Update the stale Javadoc and comments the gates named (`scripts/container-smoke.sh`,
    `AppConfigurationFlagReaderTest`, `HttpSurfaceTest`, `PropertiesValidator`), the constitution's
    Increments list, `CLAUDE.md`, and FR-004/FR-005/FR-006 in `spec.md`.
  - Green run (2026-09-29, `-Dtest.noFailFast=true`): the three T004 suites plus
    `AppConfigurationFlagReaderTest`, `HttpSurfaceTest`, `GenerationMetricsContextTest`,
    `FeatureFlagGateTest` and `StubGenerationAdaptersTest` ran 324 tests with 0 failed.
  - Full `./gradlew test` on a host with no Docker: 3502 tests, 137 failed. Every failure is a
    Testcontainers "Docker environment" failure or a context cascade from one, the same 137 as
    T003's baseline, so there are no regressions.
  - `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` are clean.
  - **Still open:** SC-002, the JaCoCo gate and the `*IT` suites, which need a Docker-backed
    `./gradlew build`.

## Phase 4: Gate remediation (round 2 leftovers)

- [x] **T006** [US2] [US3] Red tests for the round-2 notes:
  - `ConfigurationValidationTest.GenerationDownstreams`: an Id with a space after its `=`
    (`Id= ste-id`) is refused, generation on and off, naming the setting and quoting none of the
    secret, Id or host. The SDK trims the segment, not the value, so it would sign with the padded
    Id and fail only at 18:00.
  - The two plain-http refusals also assert the Id is absent. `HTTPS://` in upper case is accepted
    for a real store.
  - `FeatureFlagPropertiesTest.Parts`: an empty `;;` segment is passed over, and a space after `=`
    stays on the value.
  - No seam needed.
  - Red run (2026-09-29, `-Dtest.noFailFast=true`, `ConfigurationValidationTest` and
    `FeatureFlagPropertiesTest`): 219 tests, 2 failed, both `AssertionError` - the padded-Id case
    with generation on and with it off.
- [x] **T007** [US2] [US3] Make them pass, and tidy the notes that change no behaviour:
  - `PropertiesValidator` refuses an Id that differs from its `strip()`, as it does a padded
    endpoint.
  - `LiveFeatureFlagConfig` builds the HTTP client before the `try`, so the catch guards only the
    parse that can quote the value.
  - Javadoc: `ConfigurationClientBuilder.buildClient()` parses and throws, not `connectionString`
    (`LiveFeatureFlagConfig`, `PropertiesValidator`, `GenerationWiringContextTest`);
    `FeatureFlagProperties` says presence is generation's, readability any set string's.
  - T003's wording claims only what its evidence shows.
  - Constitution 5.1.0 and `CLAUDE.md` name the published local pair in `docker-compose.yml`
    beside the sanctioned key, as FR-005 does - a wording clarification inside the same amendment.
  - The Id's `trim()` in the published-pair check is kept, not removed: the part keeps a space after
    its `=`, so without it `Id= <published id>` would slip past that refusal.
  - Green run (2026-09-29, `-Dtest.noFailFast=true`, the eight T005 suites): 329 tests, 0 failed.
    `checkstyleMain`, `checkstyleTest`, `pmdMain` and `pmdTest` clean.
  - **Still open:** SC-002 - the `*IT` suites and the JaCoCo gate need a Docker-backed
    `./gradlew cleanTest build`.

## Phase 5: Gate remediation (round 3: repeated parts)

- [x] **T008** [US2] [US3] Red tests for repeated parts. The SDK validates every `Endpoint=` segment
  (`new URL`) and every `Secret=` segment (Base64), throwing on the first bad one, where
  `connectionStringPart` keeps only the last - so a bad-then-good repeat passed the validator and
  was refused only at `buildClient()`:
  - `ConfigurationValidationTest.GenerationDownstreams`: a repeated `Endpoint` (bad then good, and
    good then good), `Id` and `Secret`, and a mixed-case repeat (`endpoint=` + `Endpoint=`), each
    refused with generation on and off, naming the setting and quoting none of the secret, Id or
    host.
  - `ConfigurationValidationTest.PublishedLocalPair`: `Id= <published id>` against a real store and
    on a deployed pod gets the published-pair refusal itself, pinning the retained `trim()`.
  - `FeatureFlagPropertiesTest.Parts`: `connectionStringPartCount` counts every segment of a name,
    case-insensitively, and none that is not a part.
  - Seam: `FeatureFlagProperties.connectionStringPartCount`, answering at most one.
  - Red run (2026-09-29, `-Dtest.noFailFast=true`, `ConfigurationValidationTest` and
    `FeatureFlagPropertiesTest`): 236 tests, 12 failed - 10 `AssertionError` (the five repeats, with
    generation on and off) and 2 `AssertionFailedError` (the two repeat counts). The two spaced-Id
    published-pair cases pass already: they pin behaviour that exists.

## Handover

- Deployed wiring: `cpp-aks-deploy` PR #935 (93bde6a0).
- Verify on ccm-41 per `spec.md` SC-001.
