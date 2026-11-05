# Telemetry searches, aggregates and count gates

Read telemetry from Kestra flows with the same Datadog queries used in Explorer. Export rows to internal storage or compare aggregate counts before continuing a flow.

## Tasks and API operations

All operations in this table use Datadog API v2. Each needs `apiKey` and `appKey` for the same organization and site. Use Kestra secrets for both keys. Set `site` to the organization's Datadog domain. `apiUrl` is an optional proxy override.

| Task or trigger | operationId | Application key access |
|---|---|---|
| `logs.Search` | `ListLogs` | `logs_read_data` |
| `logs.Aggregate` | `AggregateLogs` | `logs_read_data` |
| `logs.Count` | `AggregateLogs` | `logs_read_data` |
| `logs.CountThreshold` | `AggregateLogs` | `logs_read_data` |
| `spans.Search` | `ListSpans` | `apm_read` |
| `spans.Aggregate` | `AggregateSpans` | `apm_read` |
| `spans.Count` | `AggregateSpans` | `apm_read` |
| `rum.Search` | `SearchRUMEvents` | RUM read permission |
| `rum.Aggregate` | `AggregateRUMEvents` | RUM read permission |
| `audit.Search` | `SearchAuditLogs` | Audit Trail read permission |
| `civisibility.SearchPipelines` | `SearchCIAppPipelineEvents` | `ci_visibility_read` |
| `civisibility.SearchTests` | `SearchCIAppTestEvents` | `ci_visibility_read` or `test_optimization_read` |
| `events.Search` | `SearchEvents` | `events_read` |

The supplied OpenAPI description inherits global key authentication for the RUM and Audit search operations. It does not declare an operation-specific OAuth scope for these operations. Enable access to those products for the application key owner.

Existing `logs.Send` and `events.Create` retain their intake and event-creation behavior.

## Search and export

Search tasks accept `query`, `from`, `to`, `sort`, `maxResults`, `fetchType` and `includeSecrets`. Log search also accepts `indexes`. The window defaults to `now-15m` through `now`.

Timestamps accept ISO-8601 instants with an offset, epoch milliseconds and Datadog relative expressions such as `now-15m` or `now-1d/d`. Validation names the invalid field. Values are sent unchanged. Use fixed instants when successive pages must share a fixed window.

The tasks follow `meta.page.after` until it ends or `maxResults` is reached. The cap defaults to 100 events and accepts 1 through 10000. Requests use pages of at most 1000. A repeated cursor fails instead of looping forever. The tasks do not follow `links.next` URLs.

- `logs.Search` keeps the existing flattened `logs` output with the event `id` beside its attributes.
- Other searches return `events` with each resource's `id`, `type` and `attributes` intact.
- `size` counts returned rows.
- With `fetchType: STORE`, rows stream into an ION file and `uri` points to internal storage. The inline list is null. The result cap still applies.
- Named API keys, application keys, passwords, tokens and HTTP URLs are redacted recursively unless `includeSecrets: true`. URL filtering includes links in free-form messages. Arbitrary credentials in prose are not parsed.

```yaml
id: export_datadog_spans
namespace: company.observability
tasks:
  - id: spans
    type: io.kestra.plugin.datadog.spans.Search
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    query: "service:orders"
    from: "2026-11-05T10:00:00Z"
    to: "2026-11-05T11:00:00Z"
    maxResults: 10000
    fetchType: STORE
```

## Aggregates

`logs.Aggregate`, `spans.Aggregate` and `rum.Aggregate` accept:

- `compute`: ordered computations with an `aggregation` enum, optional `metric`, `computeType` and `interval`. The default is a total count. Datadog returns computations as `c0`, `c1` and so on.
- `groupBy`: facet groups with `facet` and an optional positive `limit`.
- `query`, `from` and `to`: the same window rules as search.
- `timezone`: the common `options.timezone` field.
- `pageCursor`: a starting cursor for logs and RUM. Spans aggregate has no paging field and rejects this property.
- `extraBody`: recursively merged last into the complete wire body. Scalars and arrays replace typed values. Use it for histograms, group sorting, missing values, totals and advanced options. For spans, put overrides beneath `data.attributes`. Advanced fields are validated by Datadog. Overridden filter timestamps still receive local format validation.
- `maxResults`: bucket cap from 1 through 10000, default 1000.
- `fetchType` and `includeSecrets`: the same output behavior as search.

Supported aggregation values are `COUNT`, `CARDINALITY`, `PC75`, `PC90`, `PC95`, `PC98`, `PC99`, `SUM`, `MIN`, `MAX`, `AVG` and `MEDIAN`. A metric is required except for `COUNT`. `computeType` is `TOTAL` or `TIMESERIES`. An interval such as `1m` requires `TIMESERIES`.

The output has `buckets`, `size`, `uri` and `nextCursor`. Spans bucket attributes are normalized to the same `by` and `computes` shape as logs and RUM. Logs and RUM follow aggregate cursors. Spans returns a single aggregate response. A cap can truncate a page; `nextCursor` resumes after that entire page. Increase the cap to retain every bucket in that page.

```yaml
id: aggregate_datadog_logs
namespace: company.observability
tasks:
  - id: errors
    type: io.kestra.plugin.datadog.logs.Aggregate
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    query: "status:error"
    compute:
      - aggregation: COUNT
        computeType: TIMESERIES
        interval: 1m
    groupBy:
      - facet: service
        limit: 10
    extraBody:
      filter:
        storage_tier: indexes
      options:
        timezone: UTC
```

## Counts and threshold trigger

`logs.Count` and `spans.Count` request an ungrouped total count. `threshold` is required and nonnegative. Outputs are `count` and `breached`. A breach means `count > threshold`; equality does not breach. Set `failOnBreach: true` to fail the task after a breach. Empty bucket arrays mean zero. Missing or invalid counts fail instead of passing the gate.

`logs.CountThreshold` polls a sliding `window`, default `PT15M`, at `interval`, default `PT5M`. The window must contain positive whole seconds. Its threshold must be positive so the count can drop below it.

The first successful poll records the current state without starting an execution. When armed, a count above the threshold starts an execution. Sustained breaches do not fire again. A count strictly below the threshold re-arms the trigger; equality retains the previous state. The namespace KV store holds the armed flag without expiration. State changes to disarmed only after execution creation succeeds, which permits at-least-once delivery if saving state fails.

Changing the query, indexes, site, window or threshold does not reset stored state. Use a new trigger id to establish a new baseline after such changes.

HTTP 429 and 5xx skip the poll without changing state. Other HTTP errors fail the poll. A skipped poll does not backfill the skipped time window. Crossings that occur and recover between successful polls are not observed.

## Limits and verification

- Retention, indexing, sampling, permissions and Datadog rate limits determine which telemetry is available. Span counts describe retained spans rather than every ingested trace.
- HTTP 400, 403, 404, 429 and 5xx fail tasks. No custom retry loop is added. The shared HTTP client owns transport behavior.
- A timeout status or warnings in an HTTP 200 response fail the task. Counts never treat partial results as a complete answer.
- Aggregate facet limits are enforced by Datadog. `maxResults` limits output rows, not the work Datadog performs.
- Tests use the local mock server with request and response shapes derived from the supplied official OpenAPI description. No live Datadog organization was used. Product permissions, rate limits, query semantics and sampling were not verified live.

## Deliberate typed-task boundary

Configuration families are left to the generic `api.Call` catalog: log indexes, pipelines, archives, metrics, custom destinations and restriction queries; span and RUM metrics; APM and RUM retention filters. A typed Apply task for those families needs separate field-level diff and second-run idempotency evidence.

Duplicate GET search variants, v1 event listing, CI aggregates and intake, archive rehydration, error tracking, trace reads, RUM applications and sourcemaps also remain catalog operations. This milestone concentrates on telemetry extraction and workflow gates. It does not add configuration mutations.
