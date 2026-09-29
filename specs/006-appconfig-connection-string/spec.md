# Feature Specification: Read the cutover flag with the App Configuration connection string

**Feature Branch**: none - lands on `main` (no ticket)
**Created**: 2026-09-29
**Status**: Approved (design owner, 2026-09-29)

## Context

The nightly job reads the `YotResultsDistributionService` flag before it does anything else, and
fails closed. Since increment 002 the read has been made on the pod's workload identity, which
needs an `App Configuration Data Reader` role on the store. On STE (ccm-41) every read answers
`unreadable-access-denied`: the role was never granted, and it cannot be. `ccm-namespace` resolves
a role name through the cluster ConfigMap `azure-info/role-definition`, which carries no App
Configuration role, and the platform team has no date for adding one.

Every other reader of the estate's flags - resultsvalidator, the WildFly contexts - authorises with
the shared App Configuration connection string held in Key Vault as
`APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING`. This service's identity already holds
`Key Vault Secrets User` on that vault, so the secret reaches the pod through the CSI driver with
no new role assignment.

## Constitution amendment (Governance step 1)

Technology Stack, 5.0.3 → 5.1.0 (MINOR): the Feature flag bullet is re-pointed from workload
identity to the Key Vault connection string, and the Secrets/identity bullet admits it as the one
static key this service holds. No principle's wording changes.

## User Stories

- **US1 (P1)** - As the nightly job on a deployed pod, I read the flag with the connection string
  Key Vault injects, so a flag that says ON generates and one that says OFF skips.
- **US2 (P1)** - As a developer on the compose loop, I read the WireMock stub through the same
  reader with the published local pair, which authorises nothing.
- **US3 (P2)** - As support, I never find the connection string in a log line, a start-up refusal,
  a `toString()` or an HTTP response.

## Functional Requirements

- **FR-001**: `yotresultsdistribution.feature.connection-string` binds
  `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING`, with an empty default.
- **FR-002**: `yotresultsdistribution.feature.endpoint` and `yotresultsdistribution.feature.credential` are
  removed; the store is the connection string's own `Endpoint=`, and the workload-identity read is
  removed rather than kept as an option.
- **FR-003**: No connection string is no client, and every read answers `unreadable-not-configured`.
- **FR-004**: With generation enabled, start-up refuses a missing connection string. Wherever a
  string is set, generation on or off, start-up refuses one that does not carry `Endpoint=` (an
  http or https URL with a host), `Id=` and a Base64 `Secret=` - its parts read exactly as the SDK
  reads them - and one whose endpoint is a real `.azconfig.io` store, or any store while
  `servicebus.namespace` is set, over anything but https. Independently of the validator, a string
  the SDK's builder cannot parse fails the reader's construction under the setting's name with no
  cause attached, since the SDK's own message quotes the value.
- **FR-005**: Start-up refuses the published local pair (`Id=0-l0-s0:yotresultsdistributionlocal`)
  where its endpoint names a real `.azconfig.io` store or `servicebus.namespace` is set, with or
  without generation. The pair committed in `docker-compose.yml` sits outside 005's FR-034 (no
  connection string in a committed value): it authorises nothing (its secret is Base64 of
  `not-a-secret`, and no store has been given it), it is refused wherever a real flag is read, and
  it follows the precedent of the Service Bus emulator's published SAS key committed beside it.
- **FR-006**: No refusal message, no exception on the way out of start-up (cause chains included),
  no log line and no `toString()` carries the connection string or any part of it - secret, Id or
  endpoint host - but the setting's name.
- **FR-007**: The key, the label, the 2 s budget, the no-retry client and the fail-closed parsing
  are unchanged.

## Success Criteria

- **SC-001**: On ccm-41, the `flag could not be read` WARNs stop (or answer `unreadable-not-found`
  until the flag is created under `STE41`), and `GET /operations/flag` answers `ON` or `OFF`.
- **SC-002**: `./gradlew build` green, including PMD, Checkstyle and the JaCoCo gate.

## Out of Scope

- The App Configuration role assignment, and any workload-identity fallback.
- `doc/DEFECT-FIXES.md` - C16 still holds: nothing credential-shaped is committed beyond the
  published local pair, which authorises nothing.
