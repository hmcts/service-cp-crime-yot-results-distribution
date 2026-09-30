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
  http or https URL with a host), `Id=` and a Base64 `Secret=` each exactly once - its parts
  matched as the SDK matches them, and a repeated part refused because the SDK validates every
  segment of a name, not only the last - and one whose endpoint is a real `.azconfig.io` store, or any store while
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

## Addendum (2026-09-30): the connection string comes from Vault, not Key Vault

**Status**: Approved (design owner, 2026-09-30). Constitution 5.1.0 → 5.2.0 (MINOR).

### What the first deploy found

The first deploy of this increment (steccm36) refused to start: the reader's construction reported
`yotresultsdistribution.feature.connection-string could not be parsed`. The Key Vault secret this
increment was built on, `APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING` in `KV-STE-CCM-01`, holds
**only the store's URL** (`https://nle-ccp01-appconfig.azconfig.io`) - no `Id=`, no `Secret=`. It
is the value a workload-identity reader wants, not a connection string. The Context above was wrong
on two counts:

- **resultsvalidator does not read the flag with it.** Its parser rejects the value, logs one WARN
  (`Invalid feature connection string, feature toggle will default to enabled`) and answers every
  flag `getOrDefault(name, true)` - it fails open, so it has never read a flag on STE.
- **The WildFly contexts never read that secret.** They receive `java:global/featureManagerConnectionString`
  from HashiCorp Vault, `secret/ste/steccm01/cpp_feature_manager_connection_string_url`, rendered
  by the deployment's ansible into the `standaloneXml` bindings (`cpp-aks-deploy`
  `ansible/group_vars/ste/common.yaml.j2`) and mounted from a ConfigMap. `DefaultAzureFeatureFetcher`
  splits it on `;` into three positional parts and signs with the last two. The value is
  `<store URL>;Id=...;Secret=...` - the first part is the bare URL, without `Endpoint=`, which the
  legacy strips if present and so never needed; the SDK requires it. With the prefix added, it is
  the only known-good connection string on STE (confirmed on steccm41, 2026-10-01: Id and a
  strict-Base64 Secret present, first part the store's URL).

The Key Vault secret sits behind a private link, and nothing in the estate writes it; correcting it
is an ask of its owner and is tracked outside this repository.

### Decision

The deployment gives `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING` the value the WildFly
contexts read, from the same Vault path, rendered into the Helm values by the deployment's ansible -
the mechanism these values already use for the Redis key and the system user id. No code changes:
the setting, its binding and every refusal of FR-001-FR-007 are unchanged, and the service still
fails closed on a string it cannot parse.

- **FR-008**: The deployed value of `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING` is the
  estate's App Configuration connection string from Vault
  `secret/<env>/<stack>/cpp_feature_manager_connection_string_url` (on STE, `steccm01`'s), set in
  the deployment's values - not the Key Vault secret. It is still never committed to this
  repository, never defaulted, and never logged, echoed or quoted (FR-006).
- **FR-009**: When the Key Vault secret holds a full connection string, the deployment MAY return
  to it through the CSI driver; that is a values change in the infrastructure repository, and this
  addendum's exception lapses with it.

### Constitution amendment (Governance step 1)

Technology Stack, 5.1.0 → 5.2.0 (MINOR): the Feature flag bullet names the Vault path the WildFly
contexts read instead of the Key Vault secret, and the Secrets/identity bullet admits that this one
key is set in a deployment values file - the same exposure the WildFly contexts' `standalone.xml`
ConfigMap already carries. No principle's wording changes; the Cutover Rule is untouched.
