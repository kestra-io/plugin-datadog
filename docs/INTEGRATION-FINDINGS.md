# Integration findings

These findings have test reproductions. Task behavior was outside this change's scope.

## dryRun does not always mean zero requests

`SafetyInvariantsTest.everyDryRunIsAuditedForZeroRequests` runs every task that declares or inherits `dryRun` with valid inputs.

- `catalog.UpsertServiceDefinition` reads the current definition to compute a diff.
- `dashboards.Import` reads the current dashboard to compute a diff.
- `teams.SyncMembers` reads users and memberships to compute changes.
- `monitors.Apply` reads monitors and calls the remote validation endpoint.
- `deployments.CreateGate` creates a gate even with `dryRun: true`. The property selects Datadog's persisted gate evaluation mode. The current property description states this behavior.

The strict zero-request assertion fails for these tasks. The normal test checks the exact observed method sequence. It also checks that monitor validation uses the validation path. The gate-creation behavior needs a naming decision from the task owner. It must not be presented as a local preview.

## Small numeric arrays in the plugin test runtime

`SafetyInvariantsTest.smallNumericIdArrayDeserializationFindings` deserializes `monitorIds: [12345]` through the plugin's JSON mapper. `monitors.CanDelete` and `slos.Update` throw `ClassCastException` before HTTP because the array holds Integer values where the tasks expect Long values. The e2e scenarios separately exercise 64-bit identifiers. This reproduction uses the pinned Kestra core test dependency. It does not establish the behavior of every Kestra server version.

## Usage and cost date formats

`ScenarioTest` retains strict contract failures for `usage.Hourly`, `usage.Check`, `cost.Estimated` and `cost.Historical`. Their requests use hour or month precision. The pinned OpenAPI parameter schemas declare `date-time`, so the helper rejects them as incomplete timestamps. Resolve the API documentation versus schema format discrepancy before claiming strict format conformance.

## Areas without typed tasks

There is no notebook task in this integrated source tree. Notebook operations remain available through `api.Call`. The e2e inventory does not claim typed notebook coverage.

## Reproduce strict failures

Pass `DD_STRICT_SAFETY=1` into the Gradle test process and run `SafetyInvariantsTest` and `ScenarioTest`. In Docker use `--env DD_STRICT_SAFETY=1`. The normal gate asserts the known findings rather than silently skipping their tests.
