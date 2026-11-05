# How to use the Datadog plugin

Use Kestra connection properties for Datadog keys and the regional site. See each task page for its full flow examples.

## Components

| Component | Purpose |
|---|---|
| `api.Call` | Call a Datadog API operation |
| `api.Operations` | Find Datadog API operations |
| `audit.Search` | Search the Datadog audit trail |
| `cases.Assign` | Assign a Datadog case |
| `cases.Comment` | Comment on a Datadog case |
| `cases.Create` | Create a Datadog case |
| `cases.Get` | Get a Datadog case |
| `cases.Search` | Search Datadog cases |
| `cases.SetPriority` | Set a Datadog case priority |
| `cases.SetStatus` | Set a Datadog case status |
| `catalog.Delete` | Delete one Datadog service definition |
| `catalog.UpsertServiceDefinition` | Apply a Datadog service definition |
| `civisibility.SearchPipelines` | Search Datadog CI pipelines |
| `civisibility.SearchTests` | Search Datadog CI tests |
| `cost.Estimated` | Read estimated organization costs |
| `cost.Historical` | Read historical organization costs |
| `dashboards.Create` | Create a Datadog dashboard |
| `dashboards.Delete` | Delete one Datadog dashboard |
| `dashboards.Export` | Export a Datadog dashboard |
| `dashboards.Get` | Get a Datadog dashboard |
| `dashboards.Import` | Apply a Datadog dashboard definition |
| `dashboards.List` | List Datadog dashboards |
| `dashboards.Update` | Update a Datadog dashboard |
| `ddsql.Query` | Run a tabular DDSQL query |
| `deployments.CreateGate` | Create a Datadog deployment gate |
| `deployments.CreateMonitorRule` | Create a monitor-based Datadog gate rule |
| `deployments.Evaluate` | Evaluate a Datadog deployment gate |
| `deployments.GetRules` | Read Datadog deployment gate rules |
| `deployments.Record` | Record a Datadog DORA deployment |
| `downtimes.Cancel` | Cancel a Datadog downtime |
| `downtimes.Create` | Schedule a Datadog downtime that mutes monitor notifications |
| `downtimes.Get` | Get a Datadog downtime |
| `downtimes.List` | List Datadog downtimes |
| `downtimes.Update` | Update one Datadog downtime |
| `events.Create` | Post an event to the Datadog event stream |
| `events.Search` | Search Datadog events |
| `hosts.List` | List Datadog hosts |
| `hosts.Mute` | Mute one host for a bounded period |
| `hosts.Totals` | Read active and up host totals |
| `hosts.Unmute` | Unmute one host |
| `incidents.Create` | Create a Datadog incident |
| `incidents.Delete` | Delete one Datadog incident |
| `incidents.Get` | Get a Datadog incident |
| `incidents.List` | List Datadog incidents |
| `incidents.Search` | Search Datadog incidents |
| `incidents.Update` | Update one Datadog incident |
| `keys.Create` | Create a Datadog key |
| `keys.ListApiKeys` | List Datadog API keys |
| `keys.ListApplicationKeys` | List Datadog application keys |
| `keys.Revoke` | Revoke one Datadog key |
| `keys.Rotate` | Create a replacement Datadog key |
| `logs.Aggregate` | Aggregate Datadog logs |
| `logs.Count` | Count Datadog logs against a threshold |
| `logs.CountThreshold` | Start a flow when a log count crosses a threshold |
| `logs.Search` | Search Datadog logs |
| `logs.Send` | Send log entries to Datadog |
| `metrics.Check` | Check a scalar metric threshold |
| `metrics.Query` | Query timeseries metrics from Datadog |
| `metrics.QueryScalar` | Query scalar metric formulas |
| `metrics.QueryTimeseries` | Query metric timeseries formulas |
| `metrics.Submit` | Submit a custom metric point to Datadog |
| `metrics.Threshold` | Trigger on a metric threshold crossing |
| `monitors.Apply` | Apply Datadog monitors from code |
| `monitors.CanDelete` | Check whether Datadog monitors can be deleted |
| `monitors.Configuration` | Read Datadog monitor configuration |
| `monitors.Create` | Create a Datadog monitor |
| `monitors.Delete` | Delete one Datadog monitor |
| `monitors.Export` | Export Datadog monitor definitions |
| `monitors.Get` | Get one Datadog monitor |
| `monitors.List` | List Datadog monitors |
| `monitors.Search` | Search Datadog monitors with facets |
| `monitors.StateChange` | Start a flow when Datadog monitors change to an alerting state |
| `monitors.Update` | Update one Datadog monitor |
| `monitors.Validate` | Validate a Datadog monitor definition |
| `oncall.Acknowledge` | Acknowledge a Datadog On-Call page |
| `oncall.Escalate` | Escalate a Datadog On-Call page |
| `oncall.Page` | Page a Datadog On-Call team or user |
| `oncall.Resolve` | Resolve a Datadog On-Call page |
| `oncall.WhoIsOnCall` | Read the current On-Call responders for a team |
| `rum.Aggregate` | Aggregate Datadog RUM events |
| `rum.Search` | Search Datadog RUM events |
| `security.SignalsSearch` | Search Datadog security signals |
| `security.TriageSignal` | Triage one Datadog security signal |
| `slos.BudgetLow` | Start a flow when an SLO budget becomes low |
| `slos.Check` | Gate a flow on a Datadog SLO error budget |
| `slos.Create` | Create a Datadog SLO |
| `slos.Delete` | Delete one Datadog SLO |
| `slos.Get` | Get a Datadog SLO |
| `slos.History` | Read Datadog SLO history |
| `slos.List` | List Datadog SLOs |
| `slos.Update` | Update a Datadog SLO |
| `spans.Aggregate` | Aggregate Datadog spans |
| `spans.Count` | Count Datadog spans against a threshold |
| `spans.Search` | Search Datadog spans |
| `statuspages.CreateDegradation` | Create a status page degradation |
| `statuspages.Get` | Get a Datadog status page |
| `statuspages.List` | List Datadog status pages |
| `statuspages.UpdateDegradation` | Update or resolve a status page degradation |
| `synthetics.Create` | Create a Datadog synthetic test |
| `synthetics.Delete` | Delete one Datadog synthetic test |
| `synthetics.Get` | Get a Datadog synthetic test |
| `synthetics.List` | List Datadog synthetic tests |
| `synthetics.Locations` | List Datadog synthetic locations |
| `synthetics.Results` | Read Datadog synthetic test results |
| `synthetics.Run` | Run Datadog synthetic tests as a quality gate |
| `synthetics.SetStatus` | Pause or resume a Datadog synthetic test |
| `synthetics.Trigger` | Trigger Datadog synthetic CI tests |
| `synthetics.Update` | Replace a Datadog synthetic test |
| `teams.List` | List Datadog teams |
| `teams.RemoveMember` | Remove one Datadog team member |
| `teams.SyncMembers` | Sync one Datadog team's members |
| `usage.Check` | Check product usage against a budget |
| `usage.Hourly` | Read hourly product usage |
| `users.Disable` | Disable one Datadog user |
| `users.Get` | Get a Datadog user |
| `users.List` | List Datadog users |

## Dashboards and SLOs

Manage dashboards and metric or monitor SLO definitions from a flow. Export dashboard JSON to internal storage, apply it with a diff and gate a deployment on the remaining SLO error budget.

### Authentication

Every task and trigger needs both `apiKey` and `appKey`. Store them as Kestra secrets. `site` selects the region. `apiUrl` overrides the API base for a proxy or tests.

### Dashboard tasks

| Task | Datadog operationId | Behavior |
|---|---|---|
| `dashboards.List` | `ListDashboards` | Pages through summaries using `count` and `start`. Supports `filter[shared]` and `filter[deleted]`. |
| `dashboards.Get` | `GetDashboard` | Reads one definition with its nested widgets. |
| `dashboards.Create` | `CreateDashboard` | Creates a new dashboard on every run. |
| `dashboards.Update` | `UpdateDashboard` | Replaces one identified dashboard definition. |
| `dashboards.Delete` | `DeleteDashboard` | Deletes one identified dashboard. `ignoreNotFound: true` turns HTTP 404 into `deleted: false`. |
| `dashboards.Export` | `GetDashboard` | Stores a writable JSON definition in internal storage and returns `uri`. |
| `dashboards.Import` | `ListDashboards`, `GetDashboard`, `CreateDashboard`, `UpdateDashboard` | Applies a definition from `definition` or an internal-storage JSON URI in `from`. |

#### Import identity and updates

Choose exactly one identity:

#### Export and output redaction

`Export` writes JSON, including widget ids needed for tab references. Import reads that JSON as data and does not render expressions in the file a second time. It accepts only an internal-storage `kestra:` URI.

### SLO tasks

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

#### Budget calculation

`Check` calls `GET /api/v1/slo/{slo_id}/history` with:

#### Low-budget trigger

BudgetLow uses the same overall SLI and budget calculation over `window`, which defaults to `P7D`. The interval defaults to `PT5M`. The first successful sample records the current state without starting an execution, even when the budget is already low. A later transition below the minimum starts one execution. A sample at or above the minimum rearms it.

### Storage and limits

Dashboard and SLO lists default to `maxResults: 1000` and accept 1 through 10000. `truncated` tells whether more results exist. `store: true` writes an ION file and omits inline rows. A task still buffers at most its bounded result set in worker memory. Export and History write JSON. History is one API response and follows Datadog's retention and rate limits.

### Operations left to the generic API layer

These operations do not have typed tasks in this milestone. The shared `api.Call` layer is owned by the generic-layer agent. Its availability is verified by integration, not by these area tests.

### Verification

The tests use MockDatadogServer with response bodies derived from the official Datadog OpenAPI schemas. They exercise each task, validation, HTTP errors, paging, storage round trips, diff behavior, idempotency, budget thresholds and trigger transitions. Mutation evidence accompanies the milestone publication.

## End-to-end verification

The end-to-end runner loads a built plugin jar into a disposable Kestra 2.0.6 container. It uses H2, basic auth and a local mock Datadog server. It records execution JSON, execution logs, server logs and the jar digest outside the repository.

## Generic API access

### api.Call

Calls any operation in the pinned Datadog OpenAPI catalog by `operationId`. Use `api.Operations` to find its parameters, security requirements and content types. A duplicate ID must include its version, for example `v2:SubmitLog` or `v1:SubmitLog`. An ambiguous ID fails before a request.

### api.Operations

Searches catalog entries by tag substring, HTTP method or text in the operation ID, tag and path. No connection or keys are needed. Output rows include `qualifiedId`, method, path, parameters, body requirements, security and server definitions. This is discovery, not a live API call.

### Verification limits

The generated test prepares a request for every catalog entry and checks its method and path. Mock tests cover validation, response handling, all three paging styles, retry behavior and redaction. These checks do not verify a live Datadog organization, permission assignments, availability of unstable endpoints or production API limits. A prepared request proves catalog reach, not server acceptance of an arbitrary request body.

## Incident response, cases, On-Call and status pages

Use these tasks to open an incident, page responders, publish a status update and track follow-up work. All tasks need `apiKey` and `appKey`. Keep the keys in Kestra secrets.

### Typed tasks and API operations

Each row names the Datadog v2 `operationId` used by the task.

### Preview first

`incidents.Create` and `oncall.Page` default to `dryRun: true`. A preview validates the input and renders the method, URL, query and JSON body into `outputs.<task>.request`. It sends no HTTP request. Set `dryRun: false` to create the resource. Both tasks require an explicit nonblank title. Paging also requires `targetType`, `target` and `urgency`.

### Outputs

Single-resource tasks return `id`, `statusCode`, `dryRun` and `body`. Read the created page id from `outputs.page.id`. `body` preserves the response envelope and included resources. The page lifecycle endpoints return HTTP 202 with no body. Their output contains the supplied page id and the acceptance status. Acceptance does not confirm that a responder received a notification.

### Incident updates

`incidents.Update` sends only the attributes that were supplied. An unset impact flag is omitted. An explicit `customerImpacted: false` is sent. A commander-only update has no `attributes` object.

### Regional On-Call hosts

Paging uses the operation-specific host from the [On-Call Paging API reference](https://docs.datadoghq.com/api/latest/on-call-paging/).

### Limits and verification

- Creation tasks are not upserts. The shared Kestra HTTP client retries rate limits and server errors, including write requests. A repeated create can create another object. Reconcile the returned id before replaying a flow after an uncertain write.
- Degradation tasks forward an optional UUID `idempotencyKey` as `meta.idempotency_key`. Datadog owns that behavior. No local upsert or comparison is claimed.
- `Delete` operates on one incident id. `ignoreNotFound: true` treats only HTTP 404 as success. Page lifecycle tasks support the same missing-object option. Other HTTP errors fail the task after shared-client retry handling.
- Incident endpoints are marked public beta in the offline spec.
- Mock-server fixtures follow the v2 schemas. Tests cover task requests, required values, blank values, identifiers, error classes, paging, ION storage, preview guards, sparse PATCH and structured redaction. Full task examples are parsed as Kestra flows.
- No task has been tested against a live Datadog organization. Product entitlements, notification delivery, custom case statuses, field definitions and subscriber behavior have not been verified live.

### Deliberate typed-task boundary

The remaining area operations stay available through the generic `api.Call` layer. This area does not add a second operation catalog. Incident todos, timeline attachments, integrations, responders, notification configuration and postmortems remain generic operations. Case bulk updates, projects, custom attributes, links and automation rules remain generic operations. Schedule and escalation policy administration remain generic operations. Status page maintenance, templates, component administration and page publication remain generic operations.

## Metrics, hosts, usage, costs and DDSQL

These tasks query metrics, gate a flow on a value, inventory hosts and supply usage or cost data to FinOps workflows. Each task has a full flow example in its plugin documentation.

### Tasks and API operations

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

### Authentication and permissions

All new components use `apiKey` and `appKey`. Store them with Kestra secrets. Set `site` to the organization's Datadog site. `apiUrl` is an optional proxy or test override.

### Metric formulas and gates

`queries` maps variable names to metric expressions. `formulas` is a list such as `["a / b * 100"]`. `dataSource` is `METRICS` or `CLOUD_COST`. The scalar task and gate apply the selected `aggregator` to every named query. More specialized query unions can use `api.Call`.

#### Threshold trigger

`metrics.Threshold` polls a single metric query. The first numeric sample establishes a baseline without starting an execution. A false-to-true breach transition starts one flow. A sustained breach does not repeat. A sample that no longer breaches re-arms the trigger.

### Host inventory and maintenance

`hosts.List` supports `filter`, `sortField`, `sortDirection`, `from`, `includeMutedHostsData` and `includeHostsMetadata`. It sends offsets in `start` and page sizes in `count`. `pageSize` is at most 1000. `maxResults` defaults to 1000 and is capped at 100000. The output includes `count`, `totalMatching` and `truncated`. Datadog inventory changes during paging can affect offset results.

### Usage and budgets

`usage.Hourly` accepts exact UTC-hour instants. The request uses `YYYY-MM-DDThh`, as the operation description requires. Select `productFamilies` with the documented enum names. `includeDescendants`, `includeConnectedAccounts` and `includeBreakdown` default to false.

### Organization costs

`cost.Estimated` and `cost.Historical` read Datadog account charges. They expose the monthly endpoint subset with `startMonth` and optional `endMonth` in `YYYY-MM` form. `view` maps `SUMMARY` to `summary` and `SUB_ORG` to `sub-org`. Connected partner accounts are excluded by default.

### DDSQL

`ddsql.Query` sends the required `time.from_timestamp` and `time.to_timestamp` in milliseconds, even for static tables. It passes the opaque `query_id` from each running response to the next fetch. The task returns only after `completed` and fails if `maxPolls` is exhausted. The default is 30 fetches with a one-second delay.

### Internal storage

Set `store: true` on list or query tasks to receive a Kestra internal-storage `uri` instead of inline data:

### Verification and coverage

Tests use `MockDatadogServer` and response shapes from the offline Datadog v1 and v2 OpenAPI descriptions. They cover request serialization, validation before HTTP calls, 400, 403, 404, 429 and server errors, paging, no-data outcomes, storage, decimal precision and threshold state. Full examples are parsed as Kestra flows. Mutation evidence is kept with the milestone publication records.

## Monitors and downtimes

### Tasks and API operations

Every task needs `apiKey` and `appKey` for the organization's Datadog site. Store both in Kestra secrets. The tasks use the shared Datadog client and the regional API host. There is no SDK dependency.

### Monitor definitions

`Create`, `Update` and `Validate` take a `definition` object using Datadog v1 field names. The nested `definition.type` is a Datadog monitor type such as `query alert`. The top-level task `type` remains the Kestra plugin class. `Create` and `Validate` require `query` and `type`. `Update` requires a non-empty writable definition and a positive `monitorId`.

### Apply contract

Give `definitions` as YAML or JSON text or `from` as a `kestra://` file URI. Supply one object or an array. Inline text is rendered by Kestra. File contents are parsed as data without expression rendering. A single document is accepted with a maximum of 1000 definitions and 10 MiB.

### Reading and exporting

`List` returns full monitor responses with name, tags and group-state filters. `Search` uses the separate monitor search API and returns summary rows with `counts` and first-page `metadata`. Facet counts are not summed across pages. Use `Get` when a full definition is needed. `Get` can request assets and matching downtimes.

### Downtime maintenance pattern

`Create` requires a finite `end` or `duration`. Select a monitor id or monitor tags and a group scope. `List` uses v2 `page[offset]` and `page[limit]`; `currentOnly: true` returns active downtimes. `Get` reads one downtime. `Update` changes a note, scope, display time zone, first recovery notification setting or finite one-time schedule. A start change requires an end. `ignoreNotFound` is available on Update and Cancel.

### Scope boundaries and verification

The existing `StateChange` trigger covers alert transitions. Its KV cursor, first-poll behavior and retryable-poll handling are retained. It can miss transitions that occur and recover between polls. It reads at most its configured monitor limit.

## Telemetry searches, aggregates and count gates

Read telemetry from Kestra flows with the same Datadog queries used in Explorer. Export rows to internal storage or compare aggregate counts before continuing a flow.

### Tasks and API operations

All operations in this table use Datadog API v2. Each needs `apiKey` and `appKey` for the same organization and site. Use Kestra secrets for both keys. Set `site` to the organization's Datadog domain. `apiUrl` is an optional proxy override.

### Search and export

Search tasks accept `query`, `from`, `to`, `sort`, `maxResults`, `store` and `includeSecrets`. Log search also accepts `indexes`. The window defaults to `now-15m` through `now`.

### Aggregates

`logs.Aggregate`, `spans.Aggregate` and `rum.Aggregate` accept:

### Counts and threshold trigger

`logs.Count` and `spans.Count` request an ungrouped total count. `threshold` is required and nonnegative. Outputs are `count` and `breached`. A breach means `count > threshold`; equality does not breach. Set `failOnBreach: true` to fail the task after a breach. Empty bucket arrays mean zero. Missing or invalid counts fail instead of passing the gate.

### Limits and verification

- Retention, indexing, sampling, permissions and Datadog rate limits determine which telemetry is available. Span counts describe retained spans rather than every ingested trace.
- HTTP 400, 403, 404, 429 and 5xx fail tasks. No custom retry loop is added. The shared HTTP client owns transport behavior.
- A timeout status or warnings in an HTTP 200 response fail the task. Counts never treat partial results as a complete answer.
- Aggregate facet limits are enforced by Datadog. `maxResults` limits output rows, not the work Datadog performs.
- Tests use the local mock server with request and response shapes derived from the supplied official OpenAPI description. No live Datadog organization was used. Product permissions, rate limits, query semantics and sampling were not verified live.

### Deliberate typed-task boundary

Configuration families are left to the generic `api.Call` catalog: log indexes, pipelines, archives, metrics, custom destinations and restriction queries; span and RUM metrics; APM and RUM retention filters. A typed Apply task for those families needs separate field-level diff and second-run idempotency evidence.

## Organization access, keys, teams and security

These tasks use the Datadog v2 API with `apiKey` and `appKey`. Store the connection values as Kestra secrets. Set `site` to the Datadog region that owns the keys. `apiUrl` supports a proxy or a local test server.

### Tasks and API operations

| Task | What it does | v2 operationId | Required permissions |
|---|---|---|---|
| `users.List` | Pages through user and service account access metadata | `ListUsers` | `user_access_read` |
| `users.Get` | Reads one user by ID | `GetUser` | `user_access_read` |
| `users.Disable` | Disables one identified user | `DisableUser` | `user_access_manage` or `service_account_write` for the target account |
| `teams.List` | Pages through teams with an optional keyword filter | `ListTeams` | `teams_read` |
| `teams.SyncMembers` | Reconciles one team's membership from user handles | `ListUsers`, `GetTeamMemberships`, `CreateTeamMembership`, `DeleteTeamMembership` | `user_access_read`, `teams_read` and permission to manage the team |
| `teams.RemoveMember` | Removes one identified user from a team | `GetTeamMemberships`, `DeleteTeamMembership` | `teams_read` and permission to manage the team |
| `keys.ListApiKeys` | Lists API key IDs and metadata | `ListAPIKeys` | `api_keys_read` |
| `keys.ListApplicationKeys` | Lists application key IDs and scopes | `ListApplicationKeys` | `org_app_keys_read` |
| `keys.Create` | Creates an API key or a current-user application key | `CreateAPIKey`, `CreateCurrentUserApplicationKey` | `api_keys_write` or `user_app_keys` |
| `keys.Rotate` | Checks an old key then creates a replacement | `GetAPIKey`, `GetApplicationKey`, `CreateAPIKey`, `CreateCurrentUserApplicationKey` | Read permission for the old key plus create permission for the replacement |
| `keys.Revoke` | Revokes one identified key | `DeleteAPIKey`, `DeleteApplicationKey` | `api_keys_delete` or `org_app_keys_write` |
| `security.SignalsSearch` | Searches an explicit time window with cursor paging | `SearchSecurityMonitoringSignals` | `security_monitoring_signals_read` |
| `security.TriageSignal` | Changes one signal's state, assignee or archive fields | `EditSecurityMonitoringSignal` | `security_monitoring_signals_write` |
| `catalog.UpsertServiceDefinition` | Applies supplied fields to one v2.2 service definition | `GetServiceDefinition`, `CreateOrUpdateServiceDefinitions` | `apm_service_catalog_read` and `apm_service_catalog_write` |
| `catalog.Delete` | Deletes one service definition by its service name | `DeleteServiceDefinition` | `apm_service_catalog_write` |

### Key rotation and output handling

`keys.Create` and `keys.Rotate` return `keyId`, `name`, `scopes`, `preview` and `key`. `key` is `[REDACTED]` by default. The preview contains a mask and Datadog's `last4` value when returned. Set `includeSecrets: true` to return the new key value for delivery to a credential store. Do not log that output.

### Team membership sync

`teams.SyncMembers` requires an explicit `teamId` and `handles` list. Handles are exact Datadog handles after trimming. Duplicate desired handles, ambiguous inventory handles, unknown users and disabled users fail before writes. The task reads all user and membership pages within the configured limits. An incomplete inventory fails instead of being used for reconciliation.

### Lists, storage and limits

Number-paged lists accept `pageSize` from 1 to 100 and `maxResults` from 1 to 10000. Defaults are 100 and 1000. They start at page zero. Responses use total counts when present. A short final page ends a list without totals. Repeated resource IDs fail the request because they prevent a reliable inventory.

### Signal triage

`security.TriageSignal` addresses one signal. It maps `OPEN`, `UNDER_REVIEW` and `ARCHIVED` to the API's state values. `assigneeId` is a user UUID. Archive reason and comment require `state: ARCHIVED`.

### Service definition apply

`catalog.UpsertServiceDefinition` accepts one `definition` map in the v2.2 schema. It requires `schema-version: v2.2` and `dd-service`. It validates supported fields and reads the current definition with `schema_version=v2.2`.

### Coverage boundaries and verification

The typed tasks focus on access inventory, safe key lifecycle, team reconciliation, signal triage and service ownership. Other organization operations use the generic `api.Call` layer. This includes user creation and invitation, roles, team creation and links, service account key management, security rules and suppressions, CSM and application-security lists, software catalog entities and scorecards. No `security.NewSignal` trigger is included in this milestone. Scheduled signal searches are available; a trigger needs separate cursor and late-arrival semantics.

## API contracts and coverage

The plugin tests use a pinned Datadog OpenAPI description and a local mock server. The generic catalog records the source digests. No tests use a live Datadog organization.

## Synthetic tests and deployment quality gates

Use these tasks to maintain synthetic tests, run a CI batch, evaluate a deployment gate and record a completed deployment for DORA metrics. All calls use the shared Datadog client and the configured Datadog site.

### Tasks and API trace

| Task | What it does | Datadog operation IDs |
|---|---|---|
| `synthetics.List` | Lists tests or searches them with bounded paging. Supports `store: true` for ION output. | `ListTests`, `SearchTests` |
| `synthetics.Get` | Reads an API, browser or mobile test by public ID. | `GetAPITest`, `GetBrowserTest`, `GetMobileTest` |
| `synthetics.Create` | Creates an API or browser test from a full definition. | `CreateSyntheticsAPITest`, `CreateSyntheticsBrowserTest` |
| `synthetics.Update` | Replaces one identified API or browser test. | `UpdateAPITest`, `UpdateBrowserTest` |
| `synthetics.Delete` | Deletes one test without forced dependency removal. | `DeleteTests` |
| `synthetics.SetStatus` | Pauses or resumes the schedule of one test. | `UpdateTestPauseStatus` |
| `synthetics.Trigger` | Starts a CI batch and returns its ID without polling. | `TriggerCITests`, `SearchTests` |
| `synthetics.Run` | Starts a CI batch, polls to completion and enforces blocking failures. | `TriggerCITests`, `SearchTests`, `GetSyntheticsCIBatch`, `GetAPITestResult`, `GetBrowserTestResult` |
| `synthetics.Results` | Reads the latest result window or one result by ID. Supports ION output. | `GetAPITestLatestResults`, `GetBrowserTestLatestResults`, `GetAPITestResult`, `GetBrowserTestResult` |
| `synthetics.Locations` | Lists public and private location IDs and names. | `ListLocations` |
| `deployments.Evaluate` | Triggers evaluation of a configured gate for a service and environment and waits for its result. | `TriggerDeploymentGatesEvaluation`, `GetDeploymentGatesEvaluationResult` |
| `deployments.CreateGate` | Creates a service and environment gate. | `CreateDeploymentGate` |
| `deployments.CreateMonitorRule` | Adds a monitor-query rule to a gate. | `CreateDeploymentRule` |
| `deployments.GetRules` | Reads the rules of one gate. | `GetDeploymentGateRules` |
| `deployments.Record` | Records a completed deployment with Unix nanosecond timestamps. | `CreateDORADeployment` |

### Keys and scopes

| Tasks | Keys | Application-key scope from the API description |
|---|---|---|
| Synthetic reads | API and application key | `synthetics_read` |
| Synthetic writes and CI trigger | API and application key | `synthetics_write` |
| `synthetics.Run` | API and application key | `synthetics_write` and `synthetics_read` |
| `synthetics.Trigger` with search selection | API and application key | `synthetics_write` and `synthetics_read` |
| `synthetics.Locations` | API and application key | `synthetics_private_location_read` |
| Deployment gate tasks | API and application key | No named OAuth scope is specified for these operations in the offline description. The account must have access to Deployment Gates. |
| `deployments.Record` | API key | No application key required |

### Run a quality gate

Run `deployments.Record` after the actual deployment has completed. Pass its start and finish as ISO-8601 instants. `deploymentVersion` maps to Datadog's `version` field. Kestra reserves the task-level `version` property for plugin version selection.

#### Selection and overrides

Set exactly one of `testIds` or `searchQuery` on `Trigger` and `Run`. Search uses `SearchTests` before the CI trigger call. It does not send a made-up search field to `TriggerCITests`.

#### Polling decisions

`Run` and `Evaluate` use a monotonic deadline. The budget includes the trigger request, HTTP transport retries, polls and sleeps. `Run` also includes search selection and best-effort failure-detail reads. An in-flight request is cancelled at the deadline. Cancellation does not cancel the remote Datadog batch or evaluation. If the trigger response does not arrive in time, the output can have no ID even though Datadog accepted the request.

### Limits and verification

- No task claims an upsert or idempotency guarantee. Task retries can create another synthetic batch, gate, rule or deployment. The shared HTTP transport also retries HTTP 429, including POST requests. These tasks do not add a POST retry loop.
- `maxTests` is bounded from 1 to 10000. `List.limitReached` signals a capped result. `store: true` moves list or result records to an ION file and leaves the inline collection empty.
- `Results` returns the API's latest window and `lastTimestampFetched`. It does not claim to export all historical results. Use an identified result for full details.
- `Locations` and `GetRules` use unpaged API operations.
- `Delete`, `Update` and `SetStatus` accept `ignoreNotFound`. It defaults to false. Delete sends one public ID and `force_delete_dependencies=false`.
- Polling bounds the local wait. Interrupted HTTP work can still complete remotely. No cancellation endpoint is invoked on timeout.
- Tests use `MockDatadogServer` with fixtures based on the offline OpenAPI schemas. They cover selection, paging, request bodies, validation, HTTP errors, redaction, ION storage and polling decisions. They do not call a live Datadog organization.
- Live entitlement, real CI scheduling, private-location availability, gate timing and eventual DORA ingestion were not verified.
- No `TestFailed` trigger is included. A reliable state transition needs a defined aggregation policy across locations and a historical polling window. This milestone exposes CI-run decisions and result reads instead.

### Operations left to the generic API layer

The following operations have no typed wrapper in this area. They remain assigned to `api.Call` rather than duplicating its catalog. Each row states the reason for every operation in that row.

### References

- [Datadog API reference](https://docs.datadoghq.com/api/latest/)
- [Kestra plugin developer guide](https://kestra.io/docs/plugin-developer-guide)

## Verification

Tests use a local mock and the pinned Datadog OpenAPI schemas. No test has run against a live Datadog organization.
