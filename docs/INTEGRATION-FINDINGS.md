# Integration findings and repairs

The strict baseline reproduced 11 failures in SafetyInvariantsTest and ScenarioTest. Their safety and request contracts now run in the default test suite with no environment switch or known-failure bypass.

## Preview modes

Across the plugin a `dryRun` input means zero HTTP requests. A `plan` input permits GET requests to compute a diff and forbids writes.

| Task | Repair |
|---|---|
| `catalog.UpsertServiceDefinition` | Rename preview input and output flag to `plan`. Read the current definition and return its diff. |
| `dashboards.Import` | Rename preview input and output flag to `plan`. Read dashboard state and return its diff. |
| `teams.SyncMembers` | Rename preview input and output flag to `plan`. Read users and memberships. Keep the last-admin guard. |
| `monitors.Apply` | Rename preview input and output flag to `plan`. Keep local validation. Skip remote POST validation in plan mode, including when `validateFirst: true`. |
| `deployments.CreateGate` | Rename the input to `evaluationDryRun`. Both values create a gate with its persisted `dry_run` flag. The default is false. |

Update flow inputs and output references to the new names. These are clean pre-release renames. `deployments.Evaluate.dryRun` remains an output describing the server's stored mode. It is not a task preview input.

## Numeric monitor IDs

The plugin test mapper retained Integer elements inside properties declared as lists of Long. CanDelete and SLO definition validation then threw ClassCastException.

NumericIds now converts each element exactly to a 64-bit integer. CanDelete, SLO Create and SLO Update share that conversion. Small and mixed-size arrays are checked through flow deserialization. Null, fractional and overflowing IDs fail before HTTP. Positive-ID checks remain in the tasks. The source audit found no other List<Long> flow properties.

## Usage and cost timestamps

GetHourlyUsage declares date-time schemas for both timestamp bounds. Hourly and Check now send full UTC timestamps with seconds while preserving exact-hour validation.

GetEstimatedCostByOrg and GetHistoricalCostByOrg declare date-time schemas for their month parameters. Estimated and Historical still accept validated YYYY-MM inputs. Requests expand them to midnight UTC on the first day. For example, `2026-01` becomes `2026-01-01T00:00:00Z`. Request contract checks cover these formats without exceptions.

## Typed coverage boundary

Notebook operations remain available through `api.Call`. There is no typed notebook task in this source tree and the e2e inventory does not claim one. This repair adds no new endpoint.

## Verification

Run `./gradlew test` or `./gradlew check`. SafetyInvariantsTest enforces preview semantics and numeric conversion. ScenarioTest enforces every fixture's pinned request and response contracts. The real Kestra runner rejects requests in dry-run routes and non-GET requests in plan routes.

The mock and pinned schemas do not establish live Datadog acceptance, account permissions or regional behavior. No live organization was used.
