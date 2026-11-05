# Monitors and downtimes

## Tasks and API operations

Every task needs `apiKey` and `appKey` for the organization's Datadog site. Store both in Kestra secrets. The tasks use the shared Datadog client and the regional API host. There is no SDK dependency.

| Task | API operationId | Application key scope |
| --- | --- | --- |
| `monitors.List` | v1 `ListMonitors` | `monitors_read` |
| `monitors.Get` | v1 `GetMonitor` | `monitors_read` |
| `monitors.Search` | v1 `SearchMonitors` | `monitors_read` |
| `monitors.Validate` | v1 `ValidateMonitor`, `ValidateExistingMonitor` | `monitors_read` |
| `monitors.Create` | v1 `CreateMonitor` | `monitors_write` |
| `monitors.Update` | v1 `UpdateMonitor` | `monitors_write` |
| `monitors.Delete` | v1 `DeleteMonitor` | `monitors_write` |
| `monitors.CanDelete` | v1 `CheckCanDeleteMonitor` | `monitors_read` |
| `monitors.Apply` | v1 `ListMonitors`, `GetMonitor`, `ValidateMonitor`, `ValidateExistingMonitor`, `CreateMonitor`, `UpdateMonitor` | `monitors_read` and `monitors_write` |
| `monitors.Export` | v1 `ListMonitors` | `monitors_read` |
| `monitors.Configuration` | v2 `GetMonitorNotificationRules`, `GetMonitorNotificationRule`, `ListMonitorConfigPolicies`, `GetMonitorConfigPolicy`, `ListMonitorUserTemplates`, `GetMonitorUserTemplate` | `monitors_read` |
| `monitors.StateChange` | v1 `ListMonitors` | `monitors_read` |
| `downtimes.Create` | v2 `CreateDowntime` | `monitors_downtime` |
| `downtimes.Cancel` | v2 `CancelDowntime` | `monitors_downtime` |
| `downtimes.List` | v2 `ListDowntimes` | `monitors_downtime` |
| `downtimes.Get` | v2 `GetDowntime` | `monitors_downtime` |
| `downtimes.Update` | v2 `UpdateDowntime` | `monitors_downtime` |

The API also lists `monitors_draft_write` for draft monitor mutations. Organization restrictions and type-specific requirements are checked by Datadog.

## Monitor definitions

`Create`, `Update` and `Validate` take a `definition` object using Datadog v1 field names. The nested `definition.type` is a Datadog monitor type such as `query alert`. The top-level task `type` remains the Kestra plugin class. `Create` and `Validate` require `query` and `type`. `Update` requires a non-empty writable definition and a positive `monitorId`.

The supported top-level writable fields are `name`, `type`, `query`, `message`, `tags`, `options`, `priority`, `restricted_roles`, `draft_status` and `assets`. Options retain Datadog's nested object structure. Datadog validates monitor-type-specific option combinations and query syntax. `Validate` calls the API without saving. A supplied `monitorId` selects `ValidateExistingMonitor`.

`Delete` removes one identified monitor. It never sends the API's force option. Both Update and Delete accept `ignoreNotFound: true` to treat HTTP 404 as a no-op. Other failures still fail the task. `CanDelete` checks dependency errors before deletion without changing anything.

## Apply contract

Give `definitions` as YAML or JSON text or `from` as a `kestra://` file URI. Supply one object or an array. Inline text is rendered by Kestra. File contents are parsed as data without expression rendering. A single document is accepted with a maximum of 1000 definitions and 10 MiB.

Every definition requires `name`, `query`, `type` and exactly one tag of the form `managed_by:kestra:<key>`. Keys contain 1 to 128 lowercase letters, digits, dots, underscores or hyphens and begin with a letter or digit. The full tag is the stable key. Names can change. Existing monitors are never adopted by name alone. Add a management tag to an existing monitor deliberately before managing it with Apply.

The task discovers candidates through `ListMonitors` with `monitor_tags`, checks the exact tag locally and reads a matched monitor through `GetMonitor`. Duplicate input keys and duplicate matches fail. A lookup over 10000 candidates fails. Local validation, discovery and remote validation finish for the whole batch before the first persisted write.

Each result has `key`, `monitorId`, `action` and `diff`. Actions are `CREATED`, `UPDATED` and `UNCHANGED`. A diff entry has a JSON pointer `field` plus `before` and `after` values. `plan: true` reports proposed actions with GET requests only. A proposed creation has no monitor id. Local validation still runs. Remote POST validation is skipped in plan mode. `validateFirst` defaults to true for writes.

The preview input and output flag are named `plan`. Replace the former `dryRun` property and output references in flows. Across the plugin a `dryRun` input means zero HTTP requests; `plan` allows reads and forbids writes. `CanDelete.monitorIds` accepts small and 64-bit integer literals without changing their values. Fractional, null and out-of-range IDs fail before HTTP.

Only explicitly supplied writable fields are managed. Omitted fields remain unowned. Empty objects manage no children. Nested objects are compared recursively and merged with existing writable values before an update. Arrays replace the whole supplied array. Scalar JSON numeric values compare by value, so `80` and `80.0` do not create drift. Map key order does not matter.

Tag order matters. The official `Monitor.tags` schema says "Tags associated to your monitor" and defines an array of strings. It does not promise set semantics or order normalization. This implementation therefore reports reordered tags as changed. Live API ordering has not been verified.

The comparison and export remove these response fields: `id`, `created`, `modified`, `deleted`, `creator`, `multi`, `overall_state`, `overall_state_modified`, `state` and `matching_downtimes`. They also remove the read-only `options.aggregation` and `options.device_ids` fields. Unknown response fields are not projected into a writable definition. Unknown input top-level fields fail validation rather than being silently ignored.

Run Apply serially for a given management key. Datadog has no atomic create-by-tag or compare-and-swap update in these operations. A monitor deleted between discovery and update causes a failure; it is never silently recreated. A batch is not transactional. If a later write fails, earlier writes remain. Retrying reconciles the declared state again. An update response does not prove eventual consistency or prevent another writer from changing the monitor.

## Reading and exporting

`List` returns full monitor responses with name, tags and group-state filters. `Search` uses the separate monitor search API and returns summary rows with `counts` and first-page `metadata`. Facet counts are not summed across pages. Use `Get` when a full definition is needed. `Get` can request assets and matching downtimes.

`Configuration.resource` selects `NOTIFICATION_RULES`, `POLICIES` or `TEMPLATES`. Set `resourceId` to read one object. Notification rule lists use `page` and `per_page`. Policy and template list operations expose no pagination parameters. Template endpoints are marked Preview in the official description. Configuration fails rather than truncating an audit at `maxResults`.

`List`, `Search`, `Configuration`, `Apply` and `downtimes.List` support `fetchType: STORE`. They return a Kestra ION file URI instead of inline rows. Lists remain bounded in worker memory. Ordinary list and search tasks stop at their configured limit. Limits default to 1000 and allow 1 to 10000.

`Export` defaults to `fetchType: STORE`. `format: YAML` produces definitions accepted by Apply. `format: ION` produces one monitor per row for downstream processing. `FETCH` returns `definitions`, `FETCH_ONE` returns `row` and `NONE` returns only `size`. Supply `format` only with `STORE`. Export fails if `maxMonitors` would truncate the result. Add management tags before applying an export of unmanaged monitors.

The new read tasks and Apply filter values under credential-like keys and HTTP URLs by default. `includeSecrets: true` preserves them in outputs and storage files. URL filtering is conservative and also removes ordinary HTTP links. Redaction is not a parser for arbitrary secrets embedded in prose. Restore filtered placeholders before applying an export. The task never logs definition bodies. Keep credentials in Kestra secrets rather than in monitor text.

## Downtime maintenance pattern

`Create` requires a finite `end` or `duration`. Select a monitor id or monitor tags and a group scope. `List` uses v2 `page[offset]` and `page[limit]`; `currentOnly: true` returns active downtimes. `Get` reads one downtime. `Update` changes a note, scope, display time zone, first recovery notification setting or finite one-time schedule. A start change requires an end. `ignoreNotFound` is available on Update and Cancel.

A task cannot wrap later flow steps and guarantee cancellation. Use the flow's `finally` section. The finite duration also limits the mute if the runner stops before finally executes.

```yaml
id: datadog_maintenance
namespace: company.observability
tasks:
  - id: mute
    type: io.kestra.plugin.datadog.downtimes.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    monitorId: 123
    scope: env:prod
    duration: PT30M
  - id: work
    type: io.kestra.plugin.core.log.Log
    message: Run maintenance here
finally:
  - id: unmute
    type: io.kestra.plugin.datadog.downtimes.Cancel
    runIf: "{{ outputs.mute is defined }}"
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    downtimeId: "{{ outputs.mute.downtimeId }}"
    ignoreNotFound: true
```

## Scope boundaries and verification

The existing `StateChange` trigger covers alert transitions. Its KV cursor, first-poll behavior and retryable-poll handling are retained. It can miss transitions that occur and recover between polls. It reads at most its configured monitor limit.

These operations are deliberately left to the generic `api.Call` layer:

- v1 `SubmitServiceCheck`: service check intake is separate from monitor configuration.
- v1 `SearchMonitorGroups`: group search is separate from the full monitor workflow.
- Deprecated v1 `ListDowntimes`, `CreateDowntime`, `CancelDowntimesByScope`, `CancelDowntime`, `GetDowntime`, `UpdateDowntime` and `ListMonitorDowntimes`: typed downtime tasks use v2. There is no bulk cancellation task.
- v2 `ListMonitorDowntimes`: monitor-specific downtime matches remain reachable through the generic operation.
- v2 `CreateMonitorNotificationRule`, `UpdateMonitorNotificationRule`, `DeleteMonitorNotificationRule`, `CreateMonitorConfigPolicy`, `UpdateMonitorConfigPolicy` and `DeleteMonitorConfigPolicy`: this milestone provides typed audit reads and leaves organization-wide routing and policy mutations to the generic operation.
- v2 `CreateMonitorUserTemplate`, `UpdateMonitorUserTemplate`, `DeleteMonitorUserTemplate`, `ValidateMonitorUserTemplate` and `ValidateExistingMonitorUserTemplate`: versioned Preview template writes remain generic.
- Recurring schedules within v2 `CreateDowntime` and `UpdateDowntime`: the spec describes a subset of RFC 5545 without a complete RRULE grammar. The typed tasks retain finite one-time schedules.

Mock-server tests cover lifecycle calls, response envelopes, validation, paging, storage, output filtering, read-only plans and second-run idempotency. The maintenance example and task examples parse as Kestra flows. Error tests exercise HTTP 400, 403, 404, 429 and 500. A deliberate diff mutation is tested and restored before the final gate.

No test calls a live Datadog organization. Live permissions, query validation, response ordering, API normalization, eventual consistency and maintenance execution under a running Kestra scheduler are not verified by the mock tests.
