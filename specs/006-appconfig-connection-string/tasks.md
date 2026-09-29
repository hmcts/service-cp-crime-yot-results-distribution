# Tasks: App Configuration connection string

TDD red-run convention: a test task lands its compile-safe seams so the recorded red run is a
failing assertion, never a compile error; the paired implementation task quotes the green run.

Format: `[ID] [P?] [US#] Description`

## Phase 0: Governance

- [x] **T001** Amend the constitution 5.0.3 → 5.1.0 (Technology Stack: Feature flag,
  Secrets/identity); update `CLAUDE.md`, `README.md`, `docker/wiremock/README.md` and
  `.claude/rules/design_rules.md`; point `.specify/feature.json` here. Run `/speckit-analyze`
  before merge.

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

- [ ] **T003** [US1] [US2] [US3] `FeatureFlagProperties`, `LiveFeatureFlagConfig`,
  `AppConfigurationFlagReader`, `PropertiesValidator`, `application.yaml`, `docker-compose.yml`;
  delete the workload-identity flag path. `./gradlew build` green.

## Handover

- Deployed wiring: `cpp-aks-deploy` PR #935 (93bde6a0).
- Verify on ccm-41 per `spec.md` SC-001.
