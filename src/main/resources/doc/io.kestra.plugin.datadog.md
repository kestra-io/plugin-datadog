# How to use the Datadog plugin

This plugin runs Datadog from a flow: mark deployments, mute monitors for a bounded time, gate a release on a synthetic test or an SLO, keep monitors and dashboards in Git, search telemetry, page people and react when Datadog changes. Every task page has the full properties and flow examples. This page covers what is shared by all of them.

## Connect

Every task takes the same four properties.

| Property | Use |
|---|---|
| `apiKey` | Your organization's API key. Required everywhere. |
| `appKey` | An application key. Required by every task that reads data or manages things. Create it with only the scopes the task needs (each task page lists them). |
| `site` | The domain of your Datadog organization: `datadoghq.com` (default), `datadoghq.eu`, `us3.datadoghq.com`, `us5.datadoghq.com`, `ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com` or `ddog-gov.com`. A key works only on the site that issued it. |
| `apiUrl` | Replaces `https://api.<site>`. For a proxy, a private link or a test server. |

Keep both keys in Kestra secrets: `apiKey: "{{ secret('DATADOG_API_KEY') }}"`. They are sent as headers and never written to a log or an error message.

## Pick a task

| If you want to | Start with |
|---|---|
| Wait for synthetic tests before a release | `synthetics.Run` |
| Stop a flow when an error budget is low | `slos.Check` |
| Stop a flow when logs or spans show errors | `logs.Count`, `spans.Count` |
| Stop a flow when a metric is out of range | `metrics.Check` |
| Silence monitors during a deployment | `downtimes.Create`, then `downtimes.Cancel` |
| Mark a deployment on the dashboards | `events.Create`, `deployments.Record` |
| Keep monitors or dashboards in Git | `monitors.Apply`, `dashboards.Import` |
| Page a team or open an incident | `oncall.Page`, `incidents.Create` |
| Search or aggregate logs, spans, RUM, audit events | `logs.Search`, `logs.Aggregate`, `spans.Search`, `rum.Search`, `audit.Search` |
| Read usage and estimated cost | `usage.Hourly`, `cost.Estimated` |
| Reach an operation without a typed task | `api.Call` (find it with `api.Operations`) |

## Conventions shared by all tasks

- **One object at a time for anything destructive.** Delete, disable, revoke and cancel tasks take an id. A missing object fails the task unless you set `ignoreNotFound: true`.
- **Bounded mutes.** `downtimes.Create` and `hosts.Mute` need an `end` or a `duration`.
- **Preview before you apply.** Tasks that apply a definition or have side effects with people can show what they would do first. The property is named on each task page.
- **Secrets are redacted** from outputs unless you set `includeSecrets: true`.
- **Large results** go to Kestra internal storage as an ION file when `store: true`, so an execution does not carry megabytes. Search and list tasks stop at `maxResults` or `maxItems`.
- **Timestamps** accept ISO-8601 instants and, where Datadog supports them, relative forms such as `now-15m`.
- **Errors** name the HTTP status and what Datadog said. A rate limit tells you when it resets.

## Triggers

`monitors.StateChange`, `slos.BudgetLow`, `logs.CountThreshold` and `metrics.Threshold` poll Datadog and start one execution when a condition becomes true. They keep a cursor in the namespace KV store, so a condition that stays true starts one execution and not one per poll. The first poll only records the starting point. A rate limit or a server error skips that poll and the next one retries.

## What this plugin does not verify

It has not been run against a live Datadog organization. Permissions, rate limits and server-side validation on a real account are not covered by the tests. See the repository README for the test evidence.
