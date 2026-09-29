# Implementation Plan: App Configuration connection string

## Summary

Replace the workload-identity flag read with the estate's Key Vault connection string. One
client-building path, `ConfigurationClientBuilder().connectionString(cs)` with no retries and the
budget on every leg, serves both a deployed pod and the compose loop.

## Technical Context

Spring Boot 4.1, Java 25, Gradle. `com.azure:azure-data-appconfiguration` (unchanged).
`azure-identity` stays: `inbound/ServiceBusConsumerConfig` still uses it.

## Configuration

| Key | Binding | Default |
|---|---|---|
| `yotresultsdistribution.feature.connection-string` | `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING` | empty |
| `yotresultsdistribution.feature.label` | `STACK_LABEL` | empty |
| `yotresultsdistribution.feature.key` / `timeout` | - | unchanged |

Removed: `feature.endpoint` (`APPCONFIG_ENDPOINT`), `feature.credential`
(`YOTRESULTSDISTRIBUTION_FEATURE_CREDENTIAL`).

Deployed: `cpp-aks-deploy` mounts `APP-CONFIG-FEATURE-MANAGER-CONNECTION-STRING` from
`KV-STE-CCM-01` as `YOTRESULTSDISTRIBUTION_FEATURE_CONNECTION_STRING` (PR #935, 93bde6a0).

## Constitution Check

- Principle VII (no secret in output): FR-006, pinned by `ConnectionStringPrivacy` and
  `FeatureFlagPropertiesTest`.
- Cutover Rule: unchanged - one flag, one read per run, no cache, fail-closed.
- Technology Stack: amended 5.0.3 → 5.1.0.

## Changes

- `config/FeatureFlagProperties` - `(connectionString, key, label, timeout)`, masked `toString()`,
  `PUBLISHED_LOCAL_ID`.
- `config/LiveFeatureFlagConfig` - one `connectionStringClient`; workload identity removed. The
  builder's `IllegalArgumentException` (whose message quotes the whole value) is rethrown as a
  setting-named `IllegalStateException` with no cause, so no bean order is relied on.
- `adapter/appconfig/AppConfigurationFlagReader` - the `TokenCredential` constructor removed.
- `config/PropertiesValidator` - FR-004 and FR-005, messages naming settings only. The shape is
  asked of any string that is set, generation on or off; https is required of a real store or any
  store on a deployed pod. Parts are read by `FeatureFlagProperties.connectionStringPart` exactly
  as the SDK reads them.
- `application.yaml`, `docker-compose.yml`.

## Test matrix

| Test | Pins |
|---|---|
| `ConfigurationValidationTest.PublishedLocalPair` | FR-005 |
| `ConfigurationValidationTest.ConnectionStringPrivacy` | FR-004, FR-006 |
| `ConfigurationValidationTest.GenerationDownstreams` | FR-004 |
| `ConfigurationValidationTest.ShippedConfiguration` | FR-001 |
| `FeatureFlagPropertiesTest` | FR-004 (`Parts`), FR-006 |
| `GenerationWiringContextTest.FlagCredential` | FR-002, FR-003, FR-004, FR-006 |
| `adapter/appconfig/AppConfigurationFlagReaderTest` | FR-007 (unchanged) |

## Complexity Tracking

None.
