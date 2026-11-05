# Incident response, cases, On-Call and status pages

Use these tasks to open an incident, page responders, publish a status update and track follow-up work. All tasks need `apiKey` and `appKey`. Keep the keys in Kestra secrets.

## Typed tasks and API operations

Each row names the Datadog v2 `operationId` used by the task.

| Task | operationId | Purpose | Permission |
|---|---|---|---|
| `incidents.Create` | `CreateIncident` | Open an incident with title, impact flag, severity and commander | `incident_write` |
| `incidents.Get` | `GetIncident` | Read an incident by id | `incident_read` |
| `incidents.List` | `ListIncidents` | Read incidents with offset paging | `incident_read` |
| `incidents.Search` | `SearchIncidents` | Search with a query and creation sort | `incident_read` |
| `incidents.Update` | `UpdateIncident` | PATCH supplied attributes and commander | `incident_write` |
| `incidents.Delete` | `DeleteIncident` | Permanently delete one incident | `incident_write` |
| `oncall.Page` | `CreateOnCallPage` | Page one team or user | On-Call paging access |
| `oncall.Acknowledge` | `AcknowledgeOnCallPage` | Acknowledge one page | On-Call paging access |
| `oncall.Escalate` | `EscalateOnCallPage` | Escalate one page | On-Call paging access |
| `oncall.Resolve` | `ResolveOnCallPage` | Resolve one page | On-Call paging access |
| `oncall.WhoIsOnCall` | `GetTeamOnCallUsers` | Read current team responders and escalation responders | `on_call_read` |
| `cases.Create` | `CreateCase` | Create a case in a project with a case type | `cases_write` |
| `cases.Get` | `GetCase` | Read one case | `cases_read` |
| `cases.Search` | `SearchCases` | Filter and sort cases with numbered paging | `cases_read` |
| `cases.Assign` | `AssignCase` | Assign one case to a user | `cases_write` |
| `cases.SetStatus` | `UpdateStatus` | Set an existing case status name | `cases_write` |
| `cases.SetPriority` | `UpdatePriority` | Set one case priority | `cases_write` |
| `cases.Comment` | `CommentCase` | Add a timeline comment | Case write access |
| `statuspages.List` | `ListStatusPages` | Find pages by name or domain prefix | `status_pages_settings_read` |
| `statuspages.Get` | `GetStatusPage` | Read one status page | `status_pages_settings_read` |
| `statuspages.CreateDegradation` | `CreateDegradation` | Publish a degradation with affected components | `status_pages_incident_write` |
| `statuspages.UpdateDegradation` | `UpdateDegradation` | Publish an update or resolve one degradation | `status_pages_incident_write` |

The paging operations list API and application key authentication but no named OAuth scope in the offline spec. `CommentCase` inherits the API authentication declaration and lists no operation-specific scope. Check the account's product access and permissions. These tasks do not send OAuth tokens.

## Preview first

`incidents.Create` and `oncall.Page` default to `dryRun: true`. A preview validates the input and renders the method, URL, query and JSON body into `outputs.<task>.request`. It sends no HTTP request. Set `dryRun: false` to create the resource. Both tasks require an explicit nonblank title. Paging also requires `targetType`, `target` and `urgency`.

```yaml
id: preview_response
namespace: company.observability
tasks:
  - id: incident
    type: io.kestra.plugin.datadog.incidents.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    title: Orders API error rate
    customerImpacted: true
    severity: SEV_2
    dryRun: true
  - id: page
    type: io.kestra.plugin.datadog.oncall.Page
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    title: Orders API needs attention
    targetType: TEAM_HANDLE
    target: orders-oncall
    urgency: HIGH
    tags: ["service:orders", "source:kestra"]
    dryRun: true
```

The page lifecycle and degradation write tasks also default to dry run. Status page subscriber notifications default to `notifySubscribers: false`. Set that property explicitly to notify subscribers.

## Outputs

Single-resource tasks return `id`, `statusCode`, `dryRun` and `body`. Read the created page id from `outputs.page.id`. `body` preserves the response envelope and included resources. The page lifecycle endpoints return HTTP 202 with no body. Their output contains the supplied page id and the acceptance status. Acceptance does not confirm that a responder received a notification.

Dry runs return `request` and no HTTP status. Authentication headers are never included. Structured API key, application key, password, token and webhook fields are redacted recursively in responses, previews and stored lists. `includeSecrets: true` opts into their original values. Free-form title and comment text is not inspected for embedded credentials.

`cases.Comment` returns timeline cells in `body.data`, which is an array. `incidents.Search` returns the entries from `data.attributes.incidents`; each entry wraps an incident in its own `data` object.

List tasks return `items`, `count`, `uri` and `limitReached`. Use `fetchType: STORE` to stream the rows to an internal ION file instead of storing them in the execution output. The plugin accepts `pageSize` from 1 to 100 and `maxResults` from 1 to 10000. Defaults are 50 and 1000. These are plugin bounds. `limitReached` means more records can exist. Paging is not a snapshot of a changing organization.

## Incident updates

`incidents.Update` sends only the attributes that were supplied. An unset impact flag is omitted. An explicit `customerImpacted: false` is sent. A commander-only update has no `attributes` object.

Severity is a dropdown field with API values such as `SEV-2`. Common states map to `active`, `stable`, `resolved` and `completed`. Extra `fields` values use the Datadog type/value shape:

```yaml
fields:
  services:
    type: multiselect
    value: [orders]
  summary:
    type: textbox
    value: Remediation completed
```

Supported scalar field types are `dropdown` and `textbox`. Array types are `multiselect`, `textarray`, `metrictag` and `autocomplete`. A field value can be null. Do not supply the same severity or state through both the typed property and `fields`.

Case statuses are names defined by the organization's case type. `SetStatus` uses `status_name` rather than the deprecated fixed status enum. Priority is the API enum `NOT_DEFINED` or `P1` through `P5`.

## Regional On-Call hosts

Paging uses the operation-specific host from the [On-Call Paging API reference](https://docs.datadoghq.com/api/latest/on-call-paging/).

| site | Paging host |
|---|---|
| `datadoghq.com` | `navy.oncall.datadoghq.com` |
| `us3.datadoghq.com` | `teal.oncall.datadoghq.com` |
| `us5.datadoghq.com` | `coral.oncall.datadoghq.com` |
| `datadoghq.eu` | `beige.oncall.datadoghq.eu` |
| `ap1.datadoghq.com` | `saffron.oncall.datadoghq.com` |
| `ap2.datadoghq.com` | `lava.oncall.datadoghq.com` |
| `uk1.datadoghq.com` | `scarlet.oncall.datadoghq.com` |

`apiUrl` overrides the host for a proxy or mock. Other sites require an explicit override for paging. This avoids silently routing government or unknown sites to the commercial US endpoint. `WhoIsOnCall` uses the standard `api.<site>` host. The team responder endpoint defines no time query parameter, so this task reads the current responders only.

## Limits and verification

- Creation tasks are not upserts. The shared Kestra HTTP client retries rate limits and server errors, including write requests. A repeated create can create another object. Reconcile the returned id before replaying a flow after an uncertain write.
- Degradation tasks forward an optional UUID `idempotencyKey` as `meta.idempotency_key`. Datadog owns that behavior. No local upsert or comparison is claimed.
- `Delete` operates on one incident id. `ignoreNotFound: true` treats only HTTP 404 as success. Page lifecycle tasks support the same missing-object option. Other HTTP errors fail the task after shared-client retry handling.
- Incident endpoints are marked public beta in the offline spec.
- Mock-server fixtures follow the v2 schemas. Tests cover task requests, required values, blank values, identifiers, error classes, paging, ION storage, preview guards, sparse PATCH and structured redaction. Full task examples are parsed as Kestra flows.
- No task has been tested against a live Datadog organization. Product entitlements, notification delivery, custom case statuses, field definitions and subscriber behavior have not been verified live.

## Deliberate typed-task boundary

The remaining area operations stay available through the generic `api.Call` layer. This area does not add a second operation catalog. Incident todos, timeline attachments, integrations, responders, notification configuration and postmortems remain generic operations. Case bulk updates, projects, custom attributes, links and automation rules remain generic operations. Schedule and escalation policy administration remain generic operations. Status page maintenance, templates, component administration and page publication remain generic operations.

There is no incident polling trigger in this milestone. A future trigger needs separate cursor and restart evidence. There is no compound `cases.Update`: each supplied case mutation maps to one endpoint, so a task does not hide partial success across several writes.
