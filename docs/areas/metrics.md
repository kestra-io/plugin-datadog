# Metrics, hosts, usage, costs and DDSQL

These tasks query metrics, gate a flow on a value, inventory hosts and supply usage or cost data to FinOps workflows. Each task has a full flow example in its plugin documentation.

## Tasks and API operations

| Component | Datadog operationId | Purpose |
|---|---|---|
| `metrics.QueryScalar` | `QueryScalarData` | Run named metric queries and formulas with scalar results. |
| `metrics.QueryTimeseries` | `QueryTimeseriesData` | Run named queries and formulas with parallel timeseries arrays. |
| `metrics.Check` | `QueryScalarData` | Compare one scalar value with a threshold. |
| `metrics.Threshold` trigger | `QueryScalarData` | Start a flow on a threshold crossing and re-arm after recovery. |
| `hosts.List` | `ListHosts` | Read host inventory with search filters and offset paging. |
| `hosts.Totals` | `GetHostTotals` | Read active and up host counts as `Long`. |
| `hosts.Mute` | `MuteHost` | Mute one named host until an explicit end. |
| `hosts.Unmute` | `UnmuteHost` | Remove the mute from one named host. |
| `usage.Hourly` | `GetHourlyUsage` | Read product-family measurements with cursor paging. |
| `usage.Check` | `GetHourlyUsage` | Compare peak or summed usage with a budget. |
| `cost.Estimated` | `GetEstimatedCostByOrg` | Read estimated monthly Datadog charges. |
| `cost.Historical` | `GetHistoricalCostByOrg` | Read historical monthly Datadog charges. |
| `ddsql.Query` | `ExecuteDdsqlTabularQuery`, `FetchDdsqlTabularQuery` | Execute DDSQL and poll until completed. |

The existing `metrics.Submit` and `metrics.Query` tasks use v2 `SubmitMetrics` and v1 `QueryMetrics`. This change extends the metrics package alongside them.

## Authentication and permissions

All new components use `apiKey` and `appKey`. Store them with Kestra secrets. Set `site` to the organization's Datadog site. `apiUrl` is an optional proxy or test override.

| Components | Application key permissions |
|---|---|
| Metric formula queries, metric gate and threshold trigger | `timeseries_query` |
| Host inventory and totals | `hosts_read` |
| Host mute and unmute | The v1 spec inherits API and application key authentication. It does not declare a per-operation scoped permission. Confirm role access with Datadog before production use. |
| Hourly usage and usage check | `usage_read` |
| Estimated and historical cost | `usage_read` and `billing_read`; parent-level organization access |
| DDSQL | `timeseries_query` plus the key owner's role permissions for each queried data source |

## Metric formulas and gates

`queries` maps variable names to metric expressions. `formulas` is a list such as `["a / b * 100"]`. `dataSource` is `METRICS` or `CLOUD_COST`. The scalar task and gate apply the selected `aggregator` to every named query. More specialized query unions can use `api.Call`.

Windows accept `from` and `to` as instants. The task sends milliseconds. `lookback` defaults to one hour. Timeseries `queryInterval` is also milliseconds. Datadog can increase that interval to bound the response. Returned timestamps remain unchanged because the supplied spec's timeseries response description and example use conflicting units.

`metrics.Check` requires one numeric column with at most one cell. Multiple rows or numeric columns fail. Reduce grouped queries or provide one formula before using them as a gate.

`comparison` is the breach predicate: `GT`, `GTE`, `LT`, `LTE`, `EQ` or `NE`. It defaults to `GT`. `failOnBreach` defaults to true. Set it to false to receive `value`, `breached` and `noData` without failing on a breach.

Null cells, empty numeric arrays and empty column lists follow `onNoData`:

| Policy | Outcome |
|---|---|
| `FAIL` (default) | Fail even when `failOnBreach` is false. |
| `PASS` | `value: null`, `breached: false`, `noData: true`. |
| `NO_DATA` | `value: null`, `breached: null`, `noData: true`. |

Malformed responses and Datadog query errors always fail. They are not treated as missing data.

### Threshold trigger

`metrics.Threshold` polls a single metric query. The first numeric sample establishes a baseline without starting an execution. A false-to-true breach transition starts one flow. A sustained breach does not repeat. A sample that no longer breaches re-arms the trigger.

State is stored in the namespace KV store without expiration. It is scoped to namespace, flow and trigger ID. Changes to the rendered query, threshold, comparison, aggregation, window or API base establish a new baseline. No data, HTTP 429 and HTTP 5xx preserve the previous state. Other HTTP errors fail the poll.

Execution creation happens before cursor persistence. If creation fails, the next poll can emit the crossing. A failure after execution creation can cause a duplicate. Downstream actions must tolerate at-least-once delivery. Crossings and recoveries between polls are not observed. The trigger's `lookback` defaults to five minutes.

## Host inventory and maintenance

`hosts.List` supports `filter`, `sortField`, `sortDirection`, `from`, `includeMutedHostsData` and `includeHostsMetadata`. It sends offsets in `start` and page sizes in `count`. `pageSize` is at most 1000. `maxResults` defaults to 1000 and is capped at 100000. The output includes `count`, `totalMatching` and `truncated`. Datadog inventory changes during paging can affect offset results.

Datadog includes hosts seen in the past three hours by default and retains seven days of host inventory. `hosts.Totals` uses the API definitions: active means reported within the past hour and up means reported within the past two hours.

`hosts.Mute` requires exactly one of `end` or `duration`. The resulting end must be in a future epoch second. It creates a v2 downtime through the v1 host endpoint. `override` defaults to false. Setting it to true explicitly replaces existing mute settings for that host. `hosts.Unmute` sends no JSON body. Both reject blank names and wildcard selectors. `ignoreNotFound: true` converts HTTP 404 into `found: false`; other errors still fail.

## Usage and budgets

`usage.Hourly` accepts exact UTC-hour instants. The request uses `YYYY-MM-DDThh`, as the operation description requires. Select `productFamilies` with the documented enum names. `includeDescendants`, `includeConnectedAccounts` and `includeBreakdown` default to false.

The task follows `meta.pagination.next_record_id`. A result cap returns that token as `nextRecordId` with `truncated: true`. Resume with the same filters. Repeated cursors and empty pages with a continuation token fail. `pageSize` is at most 500. `maxResults` defaults to 1000 and is capped at 100000. Usage measurements are int64 or null. Counts above the exact range of binary floating point retain their integer value.

`usage.Check` uses one exact `usageType`. `MAX` checks the peak hourly measurement. `SUM` adds selected measurements across hours and organizations, so choose units deliberately. `orgId` filters by `attributes.public_id` after all pages have been read. A negative budget is rejected. The gate always fails on truncated results. Missing or null selected measurements follow the same no-data policies as the metric gate. Values are exact decimal integers.

## Organization costs

`cost.Estimated` and `cost.Historical` read Datadog account charges. They expose the monthly endpoint subset with `startMonth` and optional `endMonth` in `YYYY-MM` form. `view` maps `SUMMARY` to `summary` and `SUB_ORG` to `sub-org`. Connected partner accounts are excluded by default.

Cost outputs contain organization and account IDs, names, date, region, `totalCost` and charges. Decimal money is parsed from the original JSON text into `BigDecimal`, without a binary floating-point intermediate. No currency conversion is applied.

Estimated cost covers the current and previous month and can lag by up to 72 hours. Historical cost for a month becomes available no later than the 16th of the next month. The endpoints require parent-level organization access. Daily estimates and Cloud Cost Management configuration operations remain accessible through the generic API task.

## DDSQL

`ddsql.Query` sends the required `time.from_timestamp` and `time.to_timestamp` in milliseconds, even for static tables. It passes the opaque `query_id` from each running response to the next fetch. The task returns only after `completed` and fails if `maxPolls` is exhausted. The default is 30 fetches with a one-second delay.

`rowLimit` defaults to 1000 and cannot exceed 10000. This API has no row pagination. Check `warnings` for Datadog's truncation message. Columns retain names, DDSQL types, null values and row order. Duplicate column names are preserved. Column lengths must agree.

Sensitive column names such as `api_key`, `application_key`, `password`, `token` and `webhook_url` are redacted by default. Set `includeSecrets: true` to receive their values. This uses names rather than content inspection. Preserve sensitive source field names in SQL aliases for the filter to recognize them. The same filter applies to stored results.

## Internal storage

Set `store: true` on list or query tasks to receive a Kestra internal-storage `uri` instead of inline data:

| Task | ION record layout |
|---|---|
| `metrics.QueryScalar` | One Datadog scalar column per record. |
| `metrics.QueryTimeseries` | One record containing `series`, `times` and `values`. |
| `hosts.List` | One host per record, streamed across pages. |
| `usage.Hourly` | One usage record per record, streamed across pages. |
| Cost tasks | One typed organization cost per record. |
| `ddsql.Query` | One typed column per record. |

Host and usage storage modes avoid collecting every page in the execution output. Formula, cost and DDSQL APIs return their result in one response, which is parsed in memory before storage.

## Verification and coverage

Tests use `MockDatadogServer` and response shapes from the offline Datadog v1 and v2 OpenAPI descriptions. They cover request serialization, validation before HTTP calls, 400, 403, 404, 429 and server errors, paging, no-data outcomes, storage, decimal precision and threshold state. Full examples are parsed as Kestra flows. Mutation evidence is kept with the milestone publication records.

No test used a live Datadog organization. Production role permissions, regional endpoint behavior, account-specific billing availability, Datadog ingestion delay and DDSQL execution latency were not verified live.

The typed subset prioritizes gates, bounded maintenance, inventory and FinOps reads. [The operation coverage manifest](metrics-coverage.json) lists every scoped operation with its typed coverage or the reason it remains on `api.Call`. Deprecated product-specific usage endpoints are represented by the current hourly meter. Configuration lifecycle, reference-table writes and specialized infrastructure APIs remain generic to keep typed tasks focused on these workflow use cases.
