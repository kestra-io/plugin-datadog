# How to use the Datadog plugin

This plugin sends events, custom metrics and logs to Datadog and queries telemetry from flows. Monitor tasks create, update, reconcile, search and export definitions or check them before a change. Downtime tasks mute monitors during maintenance. A polling trigger starts a flow when monitors go into alert. Each task and the trigger have their own documentation page with full examples.

## Authentication

Datadog uses two keys and what a call needs depends on whether it sends or reads data.

- `apiKey` identifies the organization. Every task needs it.
- `appKey` is an application key. Datadog asks for it on the calls that read data or manage monitors and downtimes. Create it with only the scopes the flow needs.

| Component | Keys | Scope of the application key |
|---|---|---|
| `events.Create`, `metrics.Submit`, `logs.Send` | `apiKey` | none |
| `metrics.Query` | `apiKey`, `appKey` | `timeseries_query` |
| `monitors.List`, `monitors.Get`, `monitors.Search`, `monitors.Export`, `monitors.Configuration`, `monitors.Validate`, `monitors.CanDelete`, `monitors.StateChange` | `apiKey`, `appKey` | `monitors_read` |
| `monitors.Create`, `monitors.Update`, `monitors.Delete` | `apiKey`, `appKey` | `monitors_write` for writes; `monitors_read` for dry runs |
| `monitors.Apply` | `apiKey`, `appKey` | `monitors_read` and `monitors_write` for writes; `monitors_read` for dry runs |
| `downtimes.Create`, `downtimes.Cancel` | `apiKey`, `appKey` | `monitors_downtime` |
| `logs.Search` | `apiKey`, `appKey` | `logs_read_data` |

Store both as Kestra secrets: `apiKey: "{{ secret('DATADOG_API_KEY') }}"` and `appKey: "{{ secret('DATADOG_APP_KEY') }}"`. The keys are sent as the `DD-API-KEY` and `DD-APPLICATION-KEY` headers and are never written to a log or to an error message.

## Site

A key only works on the Datadog site of the organization that created it. Set `site` to the domain you see in the Datadog URL: `datadoghq.com` (the default), `us3.datadoghq.com`, `us5.datadoghq.com`, `datadoghq.eu`, `ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com` or `ddog-gov.com`. The tasks call `https://api.<site>` and `logs.Send` calls `https://http-intake.logs.<site>`. `apiUrl` (and `intakeUrl` for `logs.Send`) replace those addresses, for a proxy, a private link or a test server. `apiUrl` does not apply to `logs.Send`, which has its own address, so a proxy for the other tasks needs a route for the intake too.

## Tasks

### events.Create

Posts an event to the event stream, for example to mark a deployment or a pipeline run on the dashboards. `title` and `text` are required. Datadog answers `202` as soon as it has accepted the event, so it can take a moment to show in the stream.

### metrics.Submit

Sends custom metric points. Give `metric` and `value` for one point or `series` for several series in the shape of the Datadog `POST /api/v2/series` body. A point without a timestamp gets the time the task runs and a point timestamp is a number of seconds or an ISO-8601 instant. With `series`, `metricType`, `tags`, `host`, `unit`, `interval` and `timestamp` are refused: set them on each entry of `series`. Datadog accepts points up to one hour in the past and one hour in the future and processes them after it has answered, so a rejected point does not fail the task.

### metrics.Query

Runs a metrics query over a window and returns the series, with `seriesCount` and `pointCount` so that a flow can tell that a query matched nothing. A query Datadog cannot run is answered with `200` and `status: error` and the task fails with the message Datadog gives.

### monitors.List

Lists monitors by name, tags, monitor tags or group state, page by page up to `maxMonitors` (1000 by default). `fetchType` returns them in the output (`FETCH`), as the first one (`FETCH_ONE`), in internal storage (`STORE`) or only as a count (`NONE`). `groupStates` is applied by Datadog and selects monitors that have a group in that state, which is not always the monitor's `overall_state`.

Monitor data is returned unchanged, including runbook URLs and notification messages.

### monitors.Create, monitors.Update and monitors.Get

`Create` sends one `definition` object using Datadog's API field names. `Update` sends only the supplied fields to one positive `monitorId`; nested options follow Datadog's update semantics. `Get` reads one monitor and can include assets or matching downtimes. Returned monitor ids can feed subsequent tasks. `Update.ignoreNotFound: true` makes only HTTP 404 a no-op.

`Create.dryRun: true` validates the definition without creating it and returns no monitor id. `Update.dryRun: true` reads the existing definition, applies the supplied fields and validates that proposal without sending PUT. Its `updated` output stays false. Dry runs need `monitors_read`. Normal writes need `monitors_write`.

Definitions are passed to Datadog without a local list of accepted monitor types or fields. Known server-only fields are removed. Datadog checks the rest, so a new API field does not require a plugin release.

### monitors.Delete and monitors.CanDelete

`Delete` removes a single positive `monitorId` without forcing deletion of referenced monitors. Only HTTP 404 can be ignored with `ignoreNotFound: true`. `dryRun: true` reads that monitor and checks dependencies without deleting it; `deleted` stays false. A dependency conflict still fails the Delete dry run.

`CanDelete` accepts a non-empty `monitorIds` list and returns the dependency-check result on HTTP 200 or 409. `result.data.ok` contains deletable ids and `result.errors` maps referenced ids to dependency messages. Other HTTP errors fail the task.

### monitors.Validate

Asks Datadog to validate a definition without saving it. Set `monitorId` to validate a proposal for an existing monitor. Invalid definitions fail with Datadog's error. Log monitor validation also needs the log access Datadog requires for that monitor type.

### monitors.Apply

Reconciles monitors by exactly one `managed_by:kestra:<key>` tag per definition. A key uses 1 to 128 lowercase letters, digits, dots, underscores or hyphens. Duplicate input keys and multiple existing monitors with the same key fail before any write.

`from` is Kestra's `Data` input: a map, a list of maps, a JSON string or an ION file URI. Inline values are rendered through Kestra; file contents are data and are not rendered as expressions. The input is limited to 1000 definitions and 10 MiB of decoded JSON data, with a 30-second read timeout.

`dryRun: true` uses GET requests only and returns proposed `CREATED`, `UPDATED` or `UNCHANGED` actions with field diffs. A proposed creation has no id yet. Normal runs validate every definition with Datadog before the first persisted write. Only supplied definition fields are managed and unowned nested option fields are retained. Numeric representations such as 80 and 80.0 compare equal. Tag order is significant.

Run serially for each key because Datadog has no atomic upsert. A missing monitor during an update fails without recreation. A batch is not transactional. `fetchType` selects full results, the first result, an ION result file or a count.

```yaml
id: preview_datadog_monitors
namespace: company.observability
tasks:
  - id: apply
    type: io.kestra.plugin.datadog.monitors.Apply
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    dryRun: true
    from:
      - name: CPU usage
        type: query alert
        query: "avg(last_5m):avg:system.cpu.user{env:prod} > 80"
        tags: [managed_by:kestra:cpu-prod, env:prod]
        options:
          thresholds:
            critical: 80
```

### monitors.Export

Exports definition fields while removing known server-only fields. Messages, URLs and Datadog notification templates are preserved. The default `format: ION` has one definition per row and can feed `Apply.from`. Set `format: YAML` for an editable document; load edited definitions as structured `from` data or convert them to ION before applying. Each monitor needs a management tag before Apply can reconcile it.

`fetchType` defaults to `STORE`. `FETCH`, `FETCH_ONE` and `NONE` return definitions, one definition or a count instead. `format` applies only to `STORE`. Exceeding `maxMonitors` fails rather than silently producing a partial export.

### monitors.Search and monitors.Configuration

`Search` accepts Datadog's monitor search `query` and `sort`, follows pages up to `maxResults` and returns facet counts and metadata from the first page. Rows are summaries; use `Get` for a full definition.

`Configuration` reads `NOTIFICATION_RULES`, `POLICIES` or `TEMPLATES` from the v2 API. Set `resourceId` to read one object or omit it to list that resource. Notification rules are paged. Policy and template lists have no paging parameters. Exceeding `maxResults` fails instead of truncating the result. Both tasks support `fetchType`.

New read tasks and Apply diffs filter credential-like fields and HTTP URLs by default; `includeSecrets: true` preserves those values in execution output. This does not apply to Export, List or StateChange, which preserve monitor definitions and messages.

### downtimes.Create and downtimes.Cancel

`Create` mutes one monitor (`monitorId`) or the monitors with given `monitorTags`, for the groups that match `scope`. A downtime needs an end (`end` or `duration` from `start`), because a downtime without one mutes the monitors until someone cancels it and a failed flow may never do that. `Cancel` removes it. A downtime that Datadog does not find fails `Cancel` unless `ignoreNotFound` is true.

### logs.Send and logs.Search

`Send` posts one entry (`message`) or up to 1000 (`logs`) to the intake. With `logs`, `service`, `source`, `host`, `tags` and `attributes` are defaults for the entries that do not set them and `tags` are added to the `ddtags` of each entry. `attributes` refuses the keys that have a property of their own (`message`, `service`, `ddsource`, `hostname`, `ddtags`). `Search` runs a Log Explorer query over a window and follows the cursor until `maxResults` events are collected. Its `fetchType` works as the one of `monitors.List`: use `STORE` for a large result, since the output of a task is kept in the execution. Datadog limits this endpoint to 300 requests per hour.

## Triggers

### monitors.StateChange

Polls the monitors and starts one execution with the monitors that entered one of the watched `states` (`ALERT` by default) since the previous poll. The execution receives `{{ trigger.monitors }}` and `{{ trigger.count }}`.

A monitor is returned when its `overall_state` is watched and its `overall_state_modified` is later than the previous poll. `overall_state_modified` has a precision of one second, so the trigger keeps in the namespace KV store the newest value it has returned and the monitors returned at that second. A monitor that changes in the same second as another one is returned even when it is only listed by a later poll and nothing is returned twice. A monitor that stays in alert starts one execution, not one per poll. The first poll only records the starting time, taken from the `Date` header of the Datadog answer so that the clock of the worker does not matter and the monitors that already alert are not returned. The cursor moves once the execution has been created, so if creating it fails the same monitors are returned by the next poll.

Limitations:

- A monitor that goes into alert and recovers between two polls is not seen. Keep `interval` shorter than the shortest outage that matters.
- At most `maxMonitors` monitors are read per poll, in the order Datadog returns them. When the poll reaches that number it logs a warning and the monitors past it are not watched: narrow the poll with `name`, `tags` or `monitorTags`.
- A rate limit (HTTP 429), a server error (5xx) or a network failure (DNS, TLS, a reset connection, a timeout) skips that poll and the next one reads the same window again. An authentication or permission error fails the trigger.
- The automated tests use a local mock of the Datadog API that follows the published OpenAPI description. They do not cover the behavior of a live Datadog organization.
