# Dashboards and SLOs

Manage dashboards and metric or monitor SLO definitions from a flow. Export dashboard JSON to internal storage, apply it with a diff and gate a deployment on the remaining SLO error budget.

## Authentication

Every task and trigger needs both `apiKey` and `appKey`. Store them as Kestra secrets. `site` selects the region. `apiUrl` overrides the API base for a proxy or tests.

| Tasks | Application key scope |
|---|---|
| `dashboards.List`, `Get`, `Export` | `dashboards_read` |
| `dashboards.Create`, `Update`, `Delete` | `dashboards_write` |
| `dashboards.Import` | `dashboards_read` and `dashboards_write` when writing |
| `slos.List`, `Get`, `History`, `Check`, `BudgetLow` | `slos_read` |
| `slos.Create`, `Update`, `Delete` | `slos_write` |

Permissions on an individual dashboard or SLO still apply. An import with `plan: true` makes only GET requests.

## Dashboard tasks

| Task | Datadog operationId | Behavior |
|---|---|---|
| `dashboards.List` | `ListDashboards` | Pages through summaries using `count` and `start`. Supports `filter[shared]` and `filter[deleted]`. |
| `dashboards.Get` | `GetDashboard` | Reads one definition with its nested widgets. |
| `dashboards.Create` | `CreateDashboard` | Creates a new dashboard on every run. |
| `dashboards.Update` | `UpdateDashboard` | Replaces one identified dashboard definition. |
| `dashboards.Delete` | `DeleteDashboard` | Deletes one identified dashboard. `ignoreNotFound: true` turns HTTP 404 into `deleted: false`. |
| `dashboards.Export` | `GetDashboard` | Stores a writable JSON definition in internal storage and returns `uri`. |
| `dashboards.Import` | `ListDashboards`, `GetDashboard`, `CreateDashboard`, `UpdateDashboard` | Applies a definition from `definition` or an internal-storage JSON URI in `from`. |

Definitions remain maps. The required top-level fields are `title`, `layout_type` and `widgets`. The task requires a non-blank title, an `ordered` or `free` layout and a widgets array. Datadog validates the nested widget schemas.

### Import identity and updates

Choose exactly one identity:

- `dashboardId`: read and update that object. A missing id fails. Import does not create a new object with a different id in its place.
- `managingTag`: find an exact title match with that team tag. It must have the form `team:<name>` and appear in `definition.tags`. Dashboard tags represent team ownership in the official schema. Arbitrary managing tags are not supported by that schema.

Title matching reads each candidate definition because list summaries do not contain team tags. Multiple matches fail before a write. Discovery reads one extra result past `maxResults` and fails if the scan is incomplete. Use an id for a large organization or to rename a managed dashboard.

Import reports `CREATED`, `UPDATED` or `UNCHANGED`, a `plan` flag and a field-level `diff`. Each change has a JSON pointer `path`, a `before` value and an `after` value. Null represents an absent or null field. A plan reports the proposed action without writing. It has no dashboard id when planning a create.

Replace the former `dryRun` input and output references with `plan`. Across the plugin `dryRun` means zero HTTP requests and `plan` permits reads only. SLO Create and Update accept mixed small and 64-bit integer monitor IDs. Null, fractional and out-of-range IDs fail before HTTP.

On update, omitted top-level fields retain their current values. Provided nested objects and arrays replace those fields. Use explicit null or an empty array to clear a field where Datadog accepts it. Read-only top-level fields are removed: `id`, `author_handle`, `author_name`, `created_at`, `modified_at`, `url` and `experience_type`.

Diffs ignore numeric representation differences, JSON object key order and generated widget-envelope ids when the dashboard has no tabs. Ids inside widget definitions remain significant. Tabbed dashboards retain widget ids because tabs reference them. Arrays are order-sensitive. The mock tests prove that a second create/apply or update/apply makes no write and that export followed by import is a no-op.

Serialize imports for the same title/tag identity. The API has no atomic compare-and-create primitive here. A concurrent creation or update between the read and write is not locked by this task. Server-added nested defaults beyond widget ids are not normalized.

### Export and output redaction

`Export` writes JSON, including widget ids needed for tab references. Import reads that JSON as data and does not render expressions in the file a second time. It accepts only an internal-storage `kestra:` URI.

Known credential field names and webhook URLs are redacted by default in dashboard outputs, exports and diffs. Set `includeSecrets: true` to include them. Import rejects `[REDACTED]` placeholders so an export cannot overwrite a credential with a placeholder. Arbitrary credentials placed in free-form note text cannot be identified by schema or field-name redaction. Avoid putting credentials in dashboards.

```yaml
id: datadog_dashboard_round_trip
namespace: company.observability
tasks:
  - id: export
    type: io.kestra.plugin.datadog.dashboards.Export
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    dashboardId: abc-def-ghi
  - id: apply
    type: io.kestra.plugin.datadog.dashboards.Import
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    dashboardId: abc-def-ghi
    from: "{{ outputs.export.uri }}"
    plan: true
```

## SLO tasks

| Task | Datadog operationId | Behavior |
|---|---|---|
| `slos.List` | `ListSLOs` | Pages with `limit` and `offset`. Supports ids, text, tag and metric queries plus the deleted filter. |
| `slos.Get` | `GetSLO` | Reads a definition and optionally includes configured alert ids. |
| `slos.Create` | `CreateSLO` | Creates a metric or monitor SLO. Every run creates a new SLO. |
| `slos.Update` | `UpdateSLO` | Replaces the configuration of one identified metric or monitor SLO. |
| `slos.Delete` | `DeleteSLO` | Deletes one SLO without force. Confirms that the API returned its id. Supports `ignoreNotFound`. |
| `slos.History` | `GetSLOHistory` | Reads SLI history for a window. Returns the `data` object or a JSON storage URI. |
| `slos.Check` | `GetSLOHistory` | Computes the remaining budget for an explicit target and fails or returns a flag. |
| `slos.BudgetLow` | `GetSLOHistory` | Polls a rolling window and starts an execution on a healthy-to-low transition. |

Create and Update require `name`, `sloType` and non-empty `thresholds`. `METRIC` requires `numerator` and `denominator`. `MONITOR` requires positive `monitorIds`. Monitor groups require exactly one monitor and allow at most 100 groups. Each threshold has a `target` and a unique `timeframe`: `SEVEN_DAYS`, `THIRTY_DAYS` or `NINETY_DAYS`. These map to `7d`, `30d` and `90d`. Optional warnings must exceed the target and be at most 100. Datadog rejects `custom` for SLO writes, so the typed write tasks do not expose it.

Time-slice and count-specification definitions remain available through the generic API layer. Create and Update are not upsert tasks. Supply the full desired configuration on Update, including all thresholds. Delete does not force removal of an SLO referenced by another monitor.

### Budget calculation

`Check` calls `GET /api/v1/slo/{slo_id}/history` with:

- `from_ts` and `to_ts`: the required `from` and `to` properties in Unix epoch seconds.
- `target`: the explicit target percentage, strictly between 0 and 100.
- `apply_correction`: true by default. Set `applyCorrections: false` to request uncorrected history.

It reads only `data.overall.sli_value` for the SLI. It does not use the deprecated `uptime` field, individual group values or the per-timeframe `error_budget_remaining` map.

The [Datadog SLO documentation](https://docs.datadoghq.com/service_management/service_level_objectives/) defines:

```text
remainingBudget = 100 * (sli_value - target) / (100 - target)
```

All values are percentages. For SLI 99.95 and target 99.9, the remaining budget is 50%. At the target it is 0%. Below the target it is negative. The task does not clamp overspend to zero. It uses decimal arithmetic before converting the result to an output number so exact threshold equality passes.

The gate passes when `remainingBudget >= minRemainingBudget`. The minimum defaults to 20. With `failOnBreach: false`, a valid low-budget result returns `passed: false`. Missing or null SLI, non-finite or out-of-range SLI, overall query errors and replay data (`preview: true`) fail even in flag mode. Top-level history errors also fail. A group-only failure does not replace the overall SLI; use History when group-level inspection is required.

The explicit target belongs to this check. The task does not infer a configured target from the length of a custom window. The SLI has the precision Datadog reports. Decimal arithmetic cannot recover precision omitted by the API. A target of 100 cannot support a budget check because its allowed error budget is zero.

```yaml
id: datadog_budget_gate
namespace: company.observability
tasks:
  - id: budget
    type: io.kestra.plugin.datadog.slos.Check
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    sloId: 0123456789abcdef0123456789abcdef
    from: "{{ now() | dateAdd(-7, 'DAYS') | date('X') }}"
    to: "{{ now() | date('X') }}"
    target: 99.9
    minRemainingBudget: 20
```

### Low-budget trigger

BudgetLow uses the same overall SLI and budget calculation over `window`, which defaults to `P7D`. The interval defaults to `PT5M`. The first successful sample records the current state without starting an execution, even when the budget is already low. A later transition below the minimum starts one execution. A sample at or above the minimum rearms it.

The namespace KV cursor stores the previous low/healthy state and the sampling configuration. Changing the SLO id, API base, target, minimum, window or correction setting starts a new baseline. The cursor has no expiration. It moves after execution creation, so a failure during creation leaves the transition available for a retry. Delivery is at least once around execution creation.

HTTP 429 and 5xx skip the poll without changing the cursor. Authentication errors and invalid SLI data fail the poll without changing it. Crossings and recoveries that occur entirely between polls are not observed.

## Storage and limits

Dashboard and SLO lists default to `maxResults: 1000` and accept 1 through 10000. `truncated` tells whether more results exist. `fetchType: STORE` writes an ION file and omits inline rows. A task still buffers at most its bounded result set in worker memory. Export and History write JSON. History is one API response and follows Datadog's retention and rate limits.

## Operations left to the generic API layer

These operations do not have typed tasks in this milestone. The shared `api.Call` layer is owned by the generic-layer agent. Its availability is verified by integration, not by these area tests.

| operationId | Reason |
|---|---|
| `DeleteDashboards`, `RestoreDashboards` | Bulk lifecycle actions need different safety semantics from single-object tasks. |
| `ListDashboardLists`, `CreateDashboardList`, `DeleteDashboardList`, `GetDashboardList`, `UpdateDashboardList` | Dashboard list administration is outside the definition/apply and budget-gate path. |
| `DeleteDashboardListItems`, `GetDashboardListItems`, `CreateDashboardListItems`, `UpdateDashboardListItems` | Dashboard list item management stays with the generic operations. |
| `CreatePublicDashboard`, `DeletePublicDashboard`, `GetPublicDashboard`, `UpdatePublicDashboard` | Public sharing requires a separate token and access-control contract. |
| `DeletePublicDashboardInvitation`, `GetPublicDashboardInvitations`, `SendPublicDashboardInvitation` | Sharing invitation lifecycle stays with the generic operations. |
| `ListSharedDashboardsByDashboardId`, `CreateDashboardSecureEmbed`, `DeleteDashboardSecureEmbed`, `GetDashboardSecureEmbed`, `UpdateDashboardSecureEmbed` | Secure embed and sharing tokens require dedicated redaction and permission work. |
| `GetGraphSnapshot` | Snapshot rendering is outside definition management. |
| `ListNotebooks`, `CreateNotebook`, `DeleteNotebook`, `GetNotebook`, `UpdateNotebook` | Notebook content lifecycle is a separate typed-task milestone. |
| `DeleteSLOTimeframeInBulk`, `CheckCanDeleteSLO` | Bulk timeframe deletion and advisory prechecks are not needed for the non-forced single-SLO delete. |
| `ListSLOCorrection`, `CreateSLOCorrection`, `DeleteSLOCorrection`, `GetSLOCorrection`, `UpdateSLOCorrection`, `GetSLOCorrections` | Correction administration changes the measured history and is separate from read-only budget evaluation. |
| `SearchSLO` | ListSLOs already exposes the filters needed by the inventory task. |
| `GetSloStatus` | The gate uses history with an explicit target and documented calculation. |
| `ListAnnotations`, `CreateAnnotation`, `GetPageAnnotations`, `DeleteAnnotation`, `UpdateAnnotation` | Annotation administration is outside this milestone. |
| `ListDashboardsUsage`, `GetDashboardUsage` | Dashboard usage analytics are outside definition management. |
| `ListPowerpacks`, `CreatePowerpack`, `DeletePowerpack`, `GetPowerpack`, `UpdatePowerpack` | Powerpack lifecycle remains generic as directed by the scope. |
| `CreateSLOReportJob`, `GetSLOReport`, `GetSLOReportJobStatus` | Deprecated report endpoints are not wrapped in typed tasks. |
| `SearchWidgets`, `CreateWidget`, `DeleteWidget`, `GetWidget`, `UpdateWidget` | Standalone widget lifecycle remains generic as directed by the scope. |

## Verification

The tests use MockDatadogServer with response bodies derived from the official Datadog OpenAPI schemas. They exercise each task, validation, HTTP errors, paging, storage round trips, diff behavior, idempotency, budget thresholds and trigger transitions. Mutation evidence accompanies the milestone publication.

No test has run against a live Datadog organization. Live widget defaults, regional permissions, SLO replay timing, retention behavior and concurrent writes are not verified. No task contacts a live Datadog organization during tests.
